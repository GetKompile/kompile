/*
 *   Copyright 2025 Kompile Inc.
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
import { GraphEvalService } from './graph-eval.service';
import { GraphEvalStatus, GraphEvalRequest, GraphEvalResponse } from '../models/graph-eval.models';
import { backendUrl } from './base.service';

describe('GraphEvalService', () => {
  let service: GraphEvalService;
  let httpMock: HttpTestingController;
  const baseUrl = `${backendUrl}/graph-eval`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [GraphEvalService]
    });
    service = TestBed.inject(GraphEvalService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getStatus()', () => {
    it('should GET /graph-eval/status', () => {
      const mockStatus: GraphEvalStatus = {
        extractionAvailable: true,
        evaluatorCount: 3,
        evaluators: [
          { name: 'EntityPresence', type: 'ENTITY_PRESENCE', requiresLlm: false, requiresGroundTruth: true }
        ]
      };

      service.getStatus().subscribe(status => {
        expect(status).toEqual(mockStatus);
      });

      const req = httpMock.expectOne(`${baseUrl}/status`);
      expect(req.request.method).toBe('GET');
      req.flush(mockStatus);
    });
  });

  describe('runEvaluation()', () => {
    it('should POST to /graph-eval/run with request body', () => {
      const request: GraphEvalRequest = {
        sourceText: 'Alice works at Acme Corp.',
        groundTruth: {
          entities: [{ id: 'e1', title: 'Alice', type: 'PERSON' }],
          relationships: [{ source: 'Alice', target: 'Acme Corp', type: 'WORKS_AT' }]
        },
        fuzzyMatch: true,
        similarityThreshold: 0.8
      };

      const mockResponse: GraphEvalResponse = {
        success: true,
        extractedGraph: {
          entities: [{ id: 'ext-1', title: 'Alice', type: 'PERSON' }],
          relationships: []
        },
        evaluationResults: [],
        evaluationTimeMs: 150
      };

      service.runEvaluation(request).subscribe(response => {
        expect(response).toEqual(mockResponse);
      });

      const req = httpMock.expectOne(`${baseUrl}/run`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mockResponse);
    });

    it('should send request without ground truth', () => {
      const request: GraphEvalRequest = {
        sourceText: 'Some text',
        fuzzyMatch: false,
        similarityThreshold: 0.85
      };

      service.runEvaluation(request).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/run`);
      expect(req.request.body.groundTruth).toBeUndefined();
      req.flush({ success: true });
    });
  });

  describe('evaluateOnly()', () => {
    it('should POST to /graph-eval/evaluate', () => {
      const request = {
        extractedGraph: {
          entities: [{ id: 'e1', title: 'Alice', type: 'PERSON' }],
          relationships: []
        },
        groundTruth: {
          entities: [{ id: 'gt1', title: 'Alice', type: 'PERSON' }],
          relationships: []
        }
      };

      const mockResponse: GraphEvalResponse = {
        success: true,
        evaluationResults: [],
        evaluationTimeMs: 50
      };

      service.evaluateOnly(request).subscribe(response => {
        expect(response).toEqual(mockResponse);
      });

      const req = httpMock.expectOne(`${baseUrl}/evaluate`);
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);
    });
  });
});
