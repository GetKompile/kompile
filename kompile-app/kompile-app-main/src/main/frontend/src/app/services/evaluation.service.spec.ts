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

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { EvaluationService } from './evaluation.service';
import { backendUrl } from './base.service';
import {
  EvaluationConfig,
  AvailableEvaluators,
  EvaluationType,
  ToggleResponse
} from '../models/rag-management.models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test data helpers
// ═══════════════════════════════════════════════════════════════════════════════

const mockEvaluationConfig: EvaluationConfig = {
  available: true,
  enabled: true,
  async: false,
  defaultThreshold: 0.7,
  evaluators: {
    relevancy: { enabled: true, threshold: 0.7 },
    faithfulness: { enabled: true, threshold: 0.7 },
    answerCorrectness: { enabled: false, threshold: 0.5, semanticWeight: 0.6, factualWeight: 0.4 },
    contextRelevancy: { enabled: false, threshold: 0.5 },
    hallucination: { enabled: false, threshold: 0.5 },
    entityPresence: { enabled: false, threshold: 0.5, fuzzyMatch: false, similarityThreshold: 0.8 },
    relationshipPresence: { enabled: false, threshold: 0.5, fuzzyMatch: false, similarityThreshold: 0.8 },
    entityTypeAccuracy: { enabled: false, threshold: 0.5 },
    graphCompleteness: { enabled: false, threshold: 0.5 }
  }
};

const mockAvailableEvaluators: AvailableEvaluators = {
  available: true,
  serviceEnabled: true,
  evaluators: [
    { name: 'Relevancy Evaluator', type: 'RELEVANCY' },
    { name: 'Faithfulness Evaluator', type: 'FAITHFULNESS' }
  ]
};

const mockEvaluationTypes: EvaluationType[] = [
  { type: 'RELEVANCY', description: 'Checks if the response is relevant to the query' },
  { type: 'FAITHFULNESS', description: 'Checks if the response is grounded in retrieved context' },
  { type: 'ANSWER_CORRECTNESS', description: 'Checks if the answer matches the expected answer' }
];

const mockToggleResponse: ToggleResponse = {
  success: true,
  enabled: true,
  message: 'Evaluation enabled'
};

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('EvaluationService', () => {
  let service: EvaluationService;
  let httpMock: HttpTestingController;
  const baseUrl = `${backendUrl}/evaluation`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [EvaluationService]
    });

    service = TestBed.inject(EvaluationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─── 1. SERVICE CREATION ─────────────────────────────────────────────────────

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  // ─── 2. getConfig() ──────────────────────────────────────────────────────────

  describe('getConfig()', () => {
    it('should GET the evaluation config from the correct URL', () => {
      service.getConfig().subscribe();

      const req = httpMock.expectOne(`${baseUrl}/config`);
      expect(req.request.method).toBe('GET');
      req.flush(mockEvaluationConfig);
    });

    it('should return the evaluation config on success', () => {
      let result: EvaluationConfig | undefined;
      service.getConfig().subscribe(config => (result = config));

      const req = httpMock.expectOne(`${baseUrl}/config`);
      req.flush(mockEvaluationConfig);

      expect(result).toEqual(mockEvaluationConfig);
    });

    it('should propagate HTTP errors from getConfig()', () => {
      let error: any;
      service.getConfig().subscribe({ error: err => (error = err) });

      const req = httpMock.expectOne(`${baseUrl}/config`);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      expect(error).toBeTruthy();
      expect(error.status).toBe(500);
    });
  });

  // ─── 3. updateConfig() ───────────────────────────────────────────────────────

  describe('updateConfig()', () => {
    it('should PUT the config to the correct URL', () => {
      const partial: Partial<EvaluationConfig> = { enabled: true, defaultThreshold: 0.8 };
      service.updateConfig(partial).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/config`);
      expect(req.request.method).toBe('PUT');
      req.flush(mockEvaluationConfig);
    });

    it('should send the partial config in the request body', () => {
      const partial: Partial<EvaluationConfig> = { enabled: false };
      service.updateConfig(partial).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/config`);
      expect(req.request.body).toEqual(partial);
      req.flush(mockEvaluationConfig);
    });

    it('should return the updated config on success', () => {
      let result: EvaluationConfig | undefined;
      service.updateConfig({ enabled: true }).subscribe(cfg => (result = cfg));

      const req = httpMock.expectOne(`${baseUrl}/config`);
      req.flush(mockEvaluationConfig);

      expect(result).toEqual(mockEvaluationConfig);
    });

    it('should propagate HTTP errors from updateConfig()', () => {
      let error: any;
      service.updateConfig({}).subscribe({ error: err => (error = err) });

      const req = httpMock.expectOne(`${baseUrl}/config`);
      req.flush('Bad request', { status: 400, statusText: 'Bad Request' });

      expect(error.status).toBe(400);
    });
  });

  // ─── 4. getAvailableEvaluators() ─────────────────────────────────────────────

  describe('getAvailableEvaluators()', () => {
    it('should GET available evaluators from the correct URL', () => {
      service.getAvailableEvaluators().subscribe();

      const req = httpMock.expectOne(`${baseUrl}/evaluators`);
      expect(req.request.method).toBe('GET');
      req.flush(mockAvailableEvaluators);
    });

    it('should return AvailableEvaluators on success', () => {
      let result: AvailableEvaluators | undefined;
      service.getAvailableEvaluators().subscribe(ev => (result = ev));

      const req = httpMock.expectOne(`${baseUrl}/evaluators`);
      req.flush(mockAvailableEvaluators);

      expect(result).toEqual(mockAvailableEvaluators);
      expect(result?.evaluators.length).toBe(2);
    });

    it('should propagate HTTP errors from getAvailableEvaluators()', () => {
      let error: any;
      service.getAvailableEvaluators().subscribe({ error: err => (error = err) });

      const req = httpMock.expectOne(`${baseUrl}/evaluators`);
      req.flush('Not found', { status: 404, statusText: 'Not Found' });

      expect(error.status).toBe(404);
    });
  });

  // ─── 5. getEvaluationTypes() ─────────────────────────────────────────────────

  describe('getEvaluationTypes()', () => {
    it('should GET evaluation types from the correct URL', () => {
      service.getEvaluationTypes().subscribe();

      const req = httpMock.expectOne(`${baseUrl}/types`);
      expect(req.request.method).toBe('GET');
      req.flush(mockEvaluationTypes);
    });

    it('should return an array of EvaluationType on success', () => {
      let result: EvaluationType[] | undefined;
      service.getEvaluationTypes().subscribe(types => (result = types));

      const req = httpMock.expectOne(`${baseUrl}/types`);
      req.flush(mockEvaluationTypes);

      expect(result).toEqual(mockEvaluationTypes);
      expect(result?.length).toBe(3);
    });

    it('should return an empty array when no types are available', () => {
      let result: EvaluationType[] | undefined;
      service.getEvaluationTypes().subscribe(types => (result = types));

      const req = httpMock.expectOne(`${baseUrl}/types`);
      req.flush([]);

      expect(result).toEqual([]);
    });
  });

  // ─── 6. toggle() ─────────────────────────────────────────────────────────────

  describe('toggle()', () => {
    it('should POST to the correct URL when enabling', () => {
      service.toggle(true).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/toggle`);
      expect(req.request.method).toBe('POST');
      req.flush(mockToggleResponse);
    });

    it('should send enabled: true in request body', () => {
      service.toggle(true).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/toggle`);
      expect(req.request.body).toEqual({ enabled: true });
      req.flush(mockToggleResponse);
    });

    it('should send enabled: false in request body when disabling', () => {
      service.toggle(false).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/toggle`);
      expect(req.request.body).toEqual({ enabled: false });
      req.flush({ success: true, enabled: false, message: 'Evaluation disabled' });
    });

    it('should return ToggleResponse on success', () => {
      let result: ToggleResponse | undefined;
      service.toggle(true).subscribe(r => (result = r));

      const req = httpMock.expectOne(`${baseUrl}/toggle`);
      req.flush(mockToggleResponse);

      expect(result).toEqual(mockToggleResponse);
      expect(result?.success).toBeTrue();
      expect(result?.enabled).toBeTrue();
    });

    it('should propagate HTTP errors from toggle()', () => {
      let error: any;
      service.toggle(true).subscribe({ error: err => (error = err) });

      const req = httpMock.expectOne(`${baseUrl}/toggle`);
      req.flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });

      expect(error.status).toBe(401);
    });
  });

  // ─── 7. createDefaultConfig() ────────────────────────────────────────────────

  describe('createDefaultConfig()', () => {
    it('should return an EvaluationConfig with available=true and enabled=false', () => {
      const config = service.createDefaultConfig();
      expect(config.available).toBeTrue();
      expect(config.enabled).toBeFalse();
    });

    it('should return async=true in the default config', () => {
      const config = service.createDefaultConfig();
      expect(config.async).toBeTrue();
    });

    it('should set defaultThreshold to 0.5', () => {
      const config = service.createDefaultConfig();
      expect(config.defaultThreshold).toBe(0.5);
    });

    it('should have all evaluators disabled by default', () => {
      const config = service.createDefaultConfig();
      expect(config.evaluators.relevancy.enabled).toBeFalse();
      expect(config.evaluators.faithfulness.enabled).toBeFalse();
      expect(config.evaluators.answerCorrectness.enabled).toBeFalse();
      expect(config.evaluators.contextRelevancy.enabled).toBeFalse();
      expect(config.evaluators.hallucination.enabled).toBeFalse();
    });

    it('should set all evaluator thresholds to 0.5', () => {
      const config = service.createDefaultConfig();
      expect(config.evaluators.relevancy.threshold).toBe(0.5);
      expect(config.evaluators.faithfulness.threshold).toBe(0.5);
      expect(config.evaluators.answerCorrectness.threshold).toBe(0.5);
    });

    it('should set answerCorrectness semantic and factual weights to 0.5', () => {
      const config = service.createDefaultConfig();
      expect(config.evaluators.answerCorrectness.semanticWeight).toBe(0.5);
      expect(config.evaluators.answerCorrectness.factualWeight).toBe(0.5);
    });

    it('should return a new object each time it is called', () => {
      const config1 = service.createDefaultConfig();
      const config2 = service.createDefaultConfig();
      expect(config1).not.toBe(config2);
    });

    // ── Graph evaluator defaults ──────────────────────────────────────────

    it('should have all graph evaluators disabled by default', () => {
      const config = service.createDefaultConfig();
      expect(config.evaluators.entityPresence.enabled).toBeFalse();
      expect(config.evaluators.relationshipPresence.enabled).toBeFalse();
      expect(config.evaluators.entityTypeAccuracy.enabled).toBeFalse();
      expect(config.evaluators.graphCompleteness.enabled).toBeFalse();
    });

    it('should set entityPresence defaults with fuzzy match settings', () => {
      const config = service.createDefaultConfig();
      expect(config.evaluators.entityPresence.threshold).toBe(0.5);
      expect(config.evaluators.entityPresence.fuzzyMatch).toBeFalse();
      expect(config.evaluators.entityPresence.similarityThreshold).toBe(0.85);
    });

    it('should set relationshipPresence defaults with fuzzy match settings', () => {
      const config = service.createDefaultConfig();
      expect(config.evaluators.relationshipPresence.threshold).toBe(0.5);
      expect(config.evaluators.relationshipPresence.fuzzyMatch).toBeFalse();
      expect(config.evaluators.relationshipPresence.similarityThreshold).toBe(0.85);
    });

    it('should set entityTypeAccuracy threshold to 0.7', () => {
      const config = service.createDefaultConfig();
      expect(config.evaluators.entityTypeAccuracy.threshold).toBe(0.7);
    });

    it('should set graphCompleteness threshold to 0.5', () => {
      const config = service.createDefaultConfig();
      expect(config.evaluators.graphCompleteness.threshold).toBe(0.5);
    });
  });

  // ─── 8. Graph evaluator config round-trip ─────────────────────────────────

  describe('graph evaluator config round-trip', () => {
    it('should send graph evaluator config in updateConfig body', () => {
      const partial: Partial<EvaluationConfig> = {
        evaluators: {
          relevancy: { enabled: false, threshold: 0.5 },
          faithfulness: { enabled: false, threshold: 0.5 },
          answerCorrectness: { enabled: false, threshold: 0.5, semanticWeight: 0.5, factualWeight: 0.5 },
          contextRelevancy: { enabled: false, threshold: 0.5 },
          hallucination: { enabled: false, threshold: 0.5 },
          entityPresence: { enabled: true, threshold: 0.6, fuzzyMatch: true, similarityThreshold: 0.75 },
          relationshipPresence: { enabled: true, threshold: 0.7, fuzzyMatch: false, similarityThreshold: 0.8 },
          entityTypeAccuracy: { enabled: true, threshold: 0.8 },
          graphCompleteness: { enabled: false, threshold: 0.5 }
        }
      };
      service.updateConfig(partial).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/config`);
      expect(req.request.body.evaluators.entityPresence.enabled).toBeTrue();
      expect(req.request.body.evaluators.entityPresence.fuzzyMatch).toBeTrue();
      expect(req.request.body.evaluators.entityPresence.similarityThreshold).toBe(0.75);
      expect(req.request.body.evaluators.relationshipPresence.threshold).toBe(0.7);
      expect(req.request.body.evaluators.entityTypeAccuracy.enabled).toBeTrue();
      req.flush(mockEvaluationConfig);
    });

    it('should receive graph evaluator config from getConfig', () => {
      const configWithGraphEvals = {
        ...mockEvaluationConfig,
        evaluators: {
          ...mockEvaluationConfig.evaluators,
          entityPresence: { enabled: true, threshold: 0.6, fuzzyMatch: true, similarityThreshold: 0.75 },
          relationshipPresence: { enabled: true, threshold: 0.7, fuzzyMatch: false, similarityThreshold: 0.8 },
          entityTypeAccuracy: { enabled: true, threshold: 0.8 },
          graphCompleteness: { enabled: false, threshold: 0.5 }
        }
      };

      let result: EvaluationConfig | undefined;
      service.getConfig().subscribe(config => (result = config));

      const req = httpMock.expectOne(`${baseUrl}/config`);
      req.flush(configWithGraphEvals);

      expect(result?.evaluators.entityPresence.enabled).toBeTrue();
      expect(result?.evaluators.entityPresence.fuzzyMatch).toBeTrue();
      expect(result?.evaluators.entityPresence.similarityThreshold).toBe(0.75);
      expect(result?.evaluators.relationshipPresence.enabled).toBeTrue();
      expect(result?.evaluators.entityTypeAccuracy.threshold).toBe(0.8);
      expect(result?.evaluators.graphCompleteness.enabled).toBeFalse();
    });
  });
});
