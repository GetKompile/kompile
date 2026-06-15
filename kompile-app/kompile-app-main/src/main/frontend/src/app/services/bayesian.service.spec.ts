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
import { BayesianService } from './bayesian.service';
import { backendUrl } from './base.service';

describe('BayesianService', () => {
  let service: BayesianService;
  let httpMock: HttpTestingController;
  const apiBase = `${backendUrl}/attribution/bayesian`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [BayesianService]
    });
    service = TestBed.inject(BayesianService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ── Bayesian Query ──────────────────────────────────────────────────────

  describe('queryPosteriors()', () => {
    it('should POST to /bayesian/query', (done) => {
      const request = { seedNodeIds: ['node-1'], maxDepth: 2, maxNodes: 50 };
      const mockResult = {
        posteriors: { v1: 0.8 }, priors: { v1: 0.5 }, evidence: {},
        variableToNodeId: { v1: 'node-1' }, variableToTitle: { v1: 'Test' },
        variableToMebnMeta: {}, inferenceTrace: [], networkStats: {},
        computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 42
      };

      service.queryPosteriors(request).subscribe(res => {
        expect(res.posteriors).toEqual({ v1: 0.8 });
        expect(res.priors).toEqual({ v1: 0.5 });
        done();
      });

      const req = httpMock.expectOne(`${apiBase}/query`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.seedNodeIds).toEqual(['node-1']);
      req.flush(mockResult);
    });
  });

  describe('queryFromNode()', () => {
    it('should GET /bayesian/query/:nodeId with params', (done) => {
      const mockResult = {
        posteriors: { v1: 0.7 }, priors: { v1: 0.4 }, evidence: {},
        variableToNodeId: {}, variableToTitle: {}, variableToMebnMeta: {},
        inferenceTrace: [], networkStats: {},
        computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 30
      };

      service.queryFromNode('test-node', 3, 100).subscribe(res => {
        expect(res.posteriors).toEqual({ v1: 0.7 });
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url === `${apiBase}/query/test-node` &&
        r.params.get('maxDepth') === '3' &&
        r.params.get('maxNodes') === '100'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockResult);
    });
  });

  // ── MPE ──────────────────────────────────────────────────────────────────

  describe('mostProbableExplanation()', () => {
    it('should POST to /bayesian/mpe', (done) => {
      const request = { seedNodeIds: ['node-1'] };
      const mockResult = {
        assignments: { v1: 1 }, posteriors: { v1: 0.9 }, priors: { v1: 0.5 },
        evidence: {}, inferenceTrace: [], variableToNodeId: {}, variableToTitle: {},
        networkStats: {}, computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 55
      };

      service.mostProbableExplanation(request).subscribe(res => {
        expect(res.assignments).toEqual({ v1: 1 });
        expect(res.posteriors).toEqual({ v1: 0.9 });
        done();
      });

      const req = httpMock.expectOne(`${apiBase}/mpe`);
      expect(req.request.method).toBe('POST');
      req.flush(mockResult);
    });
  });

  // ── Network Stats ────────────────────────────────────────────────────────

  describe('networkStats()', () => {
    it('should GET /bayesian/network/:nodeId/stats', (done) => {
      const mockStats = { variables: 10, edges: 15, mFrags: 3 };

      service.networkStats('node-1', 3, 100).subscribe(res => {
        expect(res['variables']).toBe(10);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url === `${apiBase}/network/node-1/stats` &&
        r.params.get('maxDepth') === '3'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockStats);
    });
  });

  // ── MEBN Endpoints ───────────────────────────────────────────────────────

  describe('queryMebn()', () => {
    it('should POST to /bayesian/mebn/query', (done) => {
      const request = { seedNodeIds: ['node-1'], maxDepth: 2, maxNodes: 50 };
      const mockResult = {
        posteriors: { v1: 0.85 }, priors: { v1: 0.5 }, evidence: {},
        variableToNodeId: { v1: 'node-1' }, variableToTitle: { v1: 'Test' },
        variableToMebnMeta: { v1: { mfragName: 'Frag1', nodeRole: 'RESIDENT', rvName: 'v1' } },
        inferenceTrace: [], networkStats: {},
        computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 60
      };

      service.queryMebn(request).subscribe(res => {
        expect(res.variableToMebnMeta!['v1'].mfragName).toBe('Frag1');
        done();
      });

      const req = httpMock.expectOne(`${apiBase}/mebn/query`);
      expect(req.request.method).toBe('POST');
      req.flush(mockResult);
    });
  });

  describe('queryMebnFromNode()', () => {
    it('should GET /bayesian/mebn/query/:nodeId', (done) => {
      const mockResult = {
        posteriors: { v1: 0.8 }, priors: { v1: 0.4 }, evidence: {},
        variableToNodeId: { v1: 'node-1' }, variableToTitle: { v1: 'Node' },
        variableToMebnMeta: {}, inferenceTrace: [], networkStats: {},
        computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 45
      };

      service.queryMebnFromNode('node-1', 3, 100).subscribe(res => {
        expect(res.posteriors).toEqual({ v1: 0.8 });
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url === `${apiBase}/mebn/query/node-1` &&
        r.params.get('maxDepth') === '3'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockResult);
    });
  });

  describe('mebnStructure()', () => {
    it('should GET /bayesian/mebn/structure/:nodeId', (done) => {
      const mockResult = {
        posteriors: {}, priors: {}, evidence: {},
        variableToNodeId: {}, variableToTitle: {},
        variableToMebnMeta: { v1: { mfragName: 'F1', nodeRole: 'INPUT', rvName: 'v1' } },
        inferenceTrace: [], networkStats: { name: 'TestMTheory', mFrags: 2 },
        computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 33
      };

      service.mebnStructure('node-1').subscribe(res => {
        expect(res.networkStats?.['name']).toBe('TestMTheory');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url === `${apiBase}/mebn/structure/node-1`
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockResult);
    });
  });

  describe('mebnStats()', () => {
    it('should GET /bayesian/mebn/stats/:nodeId', (done) => {
      const mockStats = { variables: 5, mFrags: 2, entityTypes: 3 };

      service.mebnStats('node-1').subscribe(res => {
        expect(res['mFrags']).toBe(2);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url === `${apiBase}/mebn/stats/node-1`
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockStats);
    });
  });

  describe('queryMebnByType()', () => {
    it('should POST to /bayesian/mebn/query/byType with entityType', (done) => {
      const request = { seedNodeIds: ['n1'], entityType: 'DOCUMENT', maxDepth: 2, maxNodes: 50 };
      const mockResult = {
        posteriors: { v1: 0.7 }, priors: { v1: 0.3 }, evidence: {},
        variableToNodeId: { v1: 'n1' }, variableToTitle: {},
        variableToMebnMeta: { v1: { mfragName: 'DocFrag', nodeRole: 'RESIDENT', rvName: 'v1', entityType: 'DOCUMENT' } },
        inferenceTrace: [], networkStats: {},
        computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 50
      };

      service.queryMebnByType(request).subscribe(res => {
        expect(res.variableToMebnMeta!['v1'].entityType).toBe('DOCUMENT');
        done();
      });

      const req = httpMock.expectOne(`${apiBase}/mebn/query/byType`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.entityType).toBe('DOCUMENT');
      req.flush(mockResult);
    });
  });

  // ── Sensitivity & What-If ────────────────────────────────────────────────

  describe('sensitivityAnalysis()', () => {
    it('should POST to /bayesian/sensitivity', (done) => {
      const request = { seedNodeIds: ['n1'], queryNodeId: 'n2', maxDepth: 2, maxNodes: 50 };
      const mockResult = {
        sensitivities: { v1: 0.3, v2: 0.1 },
        priors: { v1: 0.5, v2: 0.6 },
        baselinePosterior: 0.75,
        queryPrior: 0.5,
        queryNodeId: 'n2',
        computationTimeMs: 28
      };

      service.sensitivityAnalysis(request).subscribe(res => {
        expect(res.sensitivities).toEqual({ v1: 0.3, v2: 0.1 });
        expect(res.baselinePosterior).toBe(0.75);
        expect(res.queryPrior).toBe(0.5);
        done();
      });

      const req = httpMock.expectOne(`${apiBase}/sensitivity`);
      expect(req.request.method).toBe('POST');
      req.flush(mockResult);
    });
  });

  describe('quickSensitivity()', () => {
    it('should GET /bayesian/sensitivity/:nodeId', (done) => {
      const mockResult = {
        sensitivities: { v1: 0.25 }, priors: { v1: 0.5 },
        baselinePosterior: 0.7, queryPrior: 0.45, queryNodeId: 'node-1',
        computationTimeMs: 15
      };

      service.quickSensitivity('node-1', 3, 50).subscribe(res => {
        expect(res.sensitivities).toEqual({ v1: 0.25 });
        expect(res.queryNodeId).toBe('node-1');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url === `${apiBase}/sensitivity/node-1` &&
        r.params.get('maxDepth') === '3'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockResult);
    });
  });

  describe('whatIfQuery()', () => {
    it('should POST to /bayesian/whatif', (done) => {
      const request = { seedNodeIds: ['n1'], hypotheticalEvidence: { v1: 1.0 }, maxDepth: 2, maxNodes: 50 };
      const mockResult = {
        posteriors: { v1: 1.0, v2: 0.9 }, priors: { v1: 0.5, v2: 0.6 }, evidence: { v1: 1.0 },
        variableToNodeId: { v1: 'n1', v2: 'n2' }, variableToTitle: {},
        variableToMebnMeta: { v1: { mfragName: 'F1', nodeRole: 'RESIDENT', rvName: 'v1' } },
        inferenceTrace: [], networkStats: {},
        computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 35
      };

      service.whatIfQuery(request).subscribe(res => {
        expect(res.posteriors['v1']).toBe(1.0);
        expect(res.evidence['v1']).toBe(1.0);
        expect(res.variableToMebnMeta!['v1'].mfragName).toBe('F1');
        done();
      });

      const req = httpMock.expectOne(`${apiBase}/whatif`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.hypotheticalEvidence).toEqual({ v1: 1.0 });
      req.flush(mockResult);
    });
  });
});
