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
import { ManagedEvalService } from './managed-eval.service';
import { environment } from '../../environments/environment';
import {
  EvalSuite,
  EvalCase,
  EvalTestResult,
  EvalSuiteResult,
  FactSheetMetrics,
  TestCasePassRate,
  CreateSuiteRequest,
  UpdateSuiteRequest,
  CreateTestCaseRequest,
  UpdateTestCaseRequest
} from '../models/managed-eval.models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test data helpers
// ═══════════════════════════════════════════════════════════════════════════════

const mockSuite: EvalSuite = {
  id: 'suite-1',
  name: 'Test Suite',
  description: 'A test suite',
  factSheetId: 42,
  enabled: true,
  requiredPassRate: 0.8,
  testCaseCount: 2,
  tags: ['regression'],
  createdAt: '2025-01-01T00:00:00Z',
  updatedAt: '2025-01-02T00:00:00Z'
};

const mockCase: EvalCase = {
  id: 'case-1',
  name: 'Test Case',
  description: 'A test case',
  factSheetId: 42,
  query: 'What is the capital of France?',
  expectedAnswer: 'Paris',
  expectedFacts: ['France has Paris as capital'],
  forbiddenFacts: [],
  expectedEntities: ['Paris'],
  evaluationTypes: ['RELEVANCY', 'FAITHFULNESS'],
  thresholds: { RELEVANCY: 0.7 },
  tags: ['geography'],
  priority: 3,
  enabled: true,
  timeoutMs: 30000
};

const mockTestResult: EvalTestResult = {
  id: 'result-1',
  testCaseId: 'case-1',
  passed: true,
  score: 0.92,
  executionTimeMs: 1200
};

const mockSuiteResult: EvalSuiteResult = {
  id: 'suite-result-1',
  suiteId: 'suite-1',
  passed: true,
  passRate: 0.9,
  averageScore: 0.88,
  passedCount: 9,
  failedCount: 1,
  skippedCount: 0,
  totalCount: 10,
  executionTimeMs: 15000
};

const mockMetrics: FactSheetMetrics = {
  totalTestRuns: 100,
  passedTestRuns: 85,
  testPassRate: 0.85,
  averageTestScore: 0.82,
  totalSuiteRuns: 20,
  passedSuiteRuns: 17,
  suitePassRate: 0.85,
  totalTestCases: 50,
  totalSuites: 5
};

const mockPassRate: TestCasePassRate = {
  passRate: 0.88,
  trend: 0.03,
  windowDays: 30
};

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('ManagedEvalService', () => {
  let service: ManagedEvalService;
  let httpMock: HttpTestingController;
  const baseUrl = `${environment.apiUrl}/eval-sets`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ManagedEvalService]
    });

    service = TestBed.inject(ManagedEvalService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─── 1. SERVICE CREATION ─────────────────────────────────────────────────────

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  // ─── 2. getAllSuites() ────────────────────────────────────────────────────────

  describe('getAllSuites()', () => {
    it('should GET all suites from the correct URL', () => {
      service.getAllSuites().subscribe();

      const req = httpMock.expectOne(baseUrl);
      expect(req.request.method).toBe('GET');
      req.flush([mockSuite]);
    });

    it('should return an array of EvalSuite on success', () => {
      let result: EvalSuite[] | undefined;
      service.getAllSuites().subscribe(suites => (result = suites));

      const req = httpMock.expectOne(baseUrl);
      req.flush([mockSuite]);

      expect(result).toEqual([mockSuite]);
    });

    it('should return an empty array when no suites exist', () => {
      let result: EvalSuite[] | undefined;
      service.getAllSuites().subscribe(suites => (result = suites));

      const req = httpMock.expectOne(baseUrl);
      req.flush([]);

      expect(result).toEqual([]);
    });
  });

  // ─── 3. getSuitesForFactSheet() ──────────────────────────────────────────────

  describe('getSuitesForFactSheet()', () => {
    it('should GET suites for a specific fact sheet', () => {
      service.getSuitesForFactSheet(42).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/fact-sheet/42`);
      expect(req.request.method).toBe('GET');
      req.flush([mockSuite]);
    });

    it('should return suites for the given fact sheet ID', () => {
      let result: EvalSuite[] | undefined;
      service.getSuitesForFactSheet(42).subscribe(s => (result = s));

      const req = httpMock.expectOne(`${baseUrl}/fact-sheet/42`);
      req.flush([mockSuite]);

      expect(result?.length).toBe(1);
      expect(result?.[0].id).toBe('suite-1');
    });

    it('should propagate HTTP errors from getSuitesForFactSheet()', () => {
      let error: any;
      service.getSuitesForFactSheet(42).subscribe({ error: err => (error = err) });

      const req = httpMock.expectOne(`${baseUrl}/fact-sheet/42`);
      req.flush('Not found', { status: 404, statusText: 'Not Found' });

      expect(error.status).toBe(404);
    });
  });

  // ─── 4. getSuiteById() ───────────────────────────────────────────────────────

  describe('getSuiteById()', () => {
    it('should GET a suite by ID', () => {
      service.getSuiteById('suite-1').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/suite-1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockSuite);
    });

    it('should return the suite on success', () => {
      let result: EvalSuite | undefined;
      service.getSuiteById('suite-1').subscribe(s => (result = s));

      const req = httpMock.expectOne(`${baseUrl}/suite-1`);
      req.flush(mockSuite);

      expect(result).toEqual(mockSuite);
    });
  });

  // ─── 5. createSuite() ────────────────────────────────────────────────────────

  describe('createSuite()', () => {
    it('should POST to create a new suite', () => {
      const request: CreateSuiteRequest = { factSheetId: 42, name: 'New Suite', description: 'desc' };
      service.createSuite(request).subscribe();

      const req = httpMock.expectOne(baseUrl);
      expect(req.request.method).toBe('POST');
      req.flush(mockSuite);
    });

    it('should send the create request in the body', () => {
      const request: CreateSuiteRequest = { factSheetId: 42, name: 'New Suite' };
      service.createSuite(request).subscribe();

      const req = httpMock.expectOne(baseUrl);
      expect(req.request.body).toEqual(request);
      req.flush(mockSuite);
    });

    it('should return the created suite', () => {
      let result: EvalSuite | undefined;
      service.createSuite({ factSheetId: 42, name: 'X' }).subscribe(s => (result = s));

      const req = httpMock.expectOne(baseUrl);
      req.flush(mockSuite);

      expect(result).toEqual(mockSuite);
    });
  });

  // ─── 6. updateSuite() ────────────────────────────────────────────────────────

  describe('updateSuite()', () => {
    it('should PUT to update a suite at the correct URL', () => {
      const update: UpdateSuiteRequest = { name: 'Updated Name', enabled: false };
      service.updateSuite('suite-1', update).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/suite-1`);
      expect(req.request.method).toBe('PUT');
      req.flush(null);
    });

    it('should send the update request body', () => {
      const update: UpdateSuiteRequest = { requiredPassRate: 0.9 };
      service.updateSuite('suite-1', update).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/suite-1`);
      expect(req.request.body).toEqual(update);
      req.flush(null);
    });
  });

  // ─── 7. deleteSuite() ────────────────────────────────────────────────────────

  describe('deleteSuite()', () => {
    it('should DELETE a suite at the correct URL', () => {
      service.deleteSuite('suite-1').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/suite-1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });

    it('should propagate HTTP errors from deleteSuite()', () => {
      let error: any;
      service.deleteSuite('suite-1').subscribe({ error: err => (error = err) });

      const req = httpMock.expectOne(`${baseUrl}/suite-1`);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      expect(error.status).toBe(500);
    });
  });

  // ─── 8. getTestCasesInSuite() ────────────────────────────────────────────────

  describe('getTestCasesInSuite()', () => {
    it('should GET test cases for a suite', () => {
      service.getTestCasesInSuite('suite-1').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/suite-1/cases`);
      expect(req.request.method).toBe('GET');
      req.flush([mockCase]);
    });

    it('should return an array of EvalCase', () => {
      let result: EvalCase[] | undefined;
      service.getTestCasesInSuite('suite-1').subscribe(c => (result = c));

      const req = httpMock.expectOne(`${baseUrl}/suite-1/cases`);
      req.flush([mockCase]);

      expect(result).toEqual([mockCase]);
    });
  });

  // ─── 9. getTestCaseById() ────────────────────────────────────────────────────

  describe('getTestCaseById()', () => {
    it('should GET a test case by ID', () => {
      service.getTestCaseById('case-1').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockCase);
    });

    it('should return the EvalCase on success', () => {
      let result: EvalCase | undefined;
      service.getTestCaseById('case-1').subscribe(c => (result = c));

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1`);
      req.flush(mockCase);

      expect(result).toEqual(mockCase);
    });
  });

  // ─── 10. createTestCase() ────────────────────────────────────────────────────

  describe('createTestCase()', () => {
    it('should POST to create a test case in the given suite', () => {
      const request: CreateTestCaseRequest = { name: 'TC', query: 'Q?' };
      service.createTestCase('suite-1', request).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/suite-1/cases`);
      expect(req.request.method).toBe('POST');
      req.flush(mockCase);
    });

    it('should send the request body', () => {
      const request: CreateTestCaseRequest = { name: 'TC', query: 'Q?', expectedAnswer: 'A' };
      service.createTestCase('suite-1', request).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/suite-1/cases`);
      expect(req.request.body).toEqual(request);
      req.flush(mockCase);
    });

    it('should return the created EvalCase', () => {
      let result: EvalCase | undefined;
      service.createTestCase('suite-1', { name: 'TC', query: 'Q?' }).subscribe(c => (result = c));

      const req = httpMock.expectOne(`${baseUrl}/suite-1/cases`);
      req.flush(mockCase);

      expect(result).toEqual(mockCase);
    });
  });

  // ─── 11. updateTestCase() ────────────────────────────────────────────────────

  describe('updateTestCase()', () => {
    it('should PUT to update a test case', () => {
      const update: UpdateTestCaseRequest = { name: 'Updated TC', enabled: false };
      service.updateTestCase('case-1', update).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1`);
      expect(req.request.method).toBe('PUT');
      req.flush(null);
    });

    it('should send the update body', () => {
      const update: UpdateTestCaseRequest = { priority: 5 };
      service.updateTestCase('case-1', update).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1`);
      expect(req.request.body).toEqual(update);
      req.flush(null);
    });
  });

  // ─── 12. deleteTestCase() ────────────────────────────────────────────────────

  describe('deleteTestCase()', () => {
    it('should DELETE a test case at the correct URL', () => {
      service.deleteTestCase('case-1').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });
  });

  // ─── 13. moveTestCase() ──────────────────────────────────────────────────────

  describe('moveTestCase()', () => {
    it('should POST to move a test case', () => {
      service.moveTestCase('case-1', 'suite-2').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1/move`);
      expect(req.request.method).toBe('POST');
      req.flush(null);
    });

    it('should send targetSuiteId in body', () => {
      service.moveTestCase('case-1', 'suite-2').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1/move`);
      expect(req.request.body).toEqual({ targetSuiteId: 'suite-2' });
      req.flush(null);
    });

    it('should send undefined targetSuiteId when null is passed', () => {
      service.moveTestCase('case-1', null).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1/move`);
      expect(req.request.body).toEqual({ targetSuiteId: undefined });
      req.flush(null);
    });
  });

  // ─── 14. getSuiteResultHistory() ─────────────────────────────────────────────

  describe('getSuiteResultHistory()', () => {
    it('should GET suite result history with default limit', () => {
      service.getSuiteResultHistory('suite-1').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/suite-1/results?limit=10`);
      expect(req.request.method).toBe('GET');
      req.flush([mockSuiteResult]);
    });

    it('should use the provided limit parameter', () => {
      service.getSuiteResultHistory('suite-1', 5).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/suite-1/results?limit=5`);
      expect(req.request.method).toBe('GET');
      req.flush([mockSuiteResult]);
    });

    it('should return an array of EvalSuiteResult', () => {
      let result: EvalSuiteResult[] | undefined;
      service.getSuiteResultHistory('suite-1').subscribe(r => (result = r));

      const req = httpMock.expectOne(`${baseUrl}/suite-1/results?limit=10`);
      req.flush([mockSuiteResult]);

      expect(result).toEqual([mockSuiteResult]);
    });
  });

  // ─── 15. getTestCaseResultHistory() ──────────────────────────────────────────

  describe('getTestCaseResultHistory()', () => {
    it('should GET test case result history with default limit', () => {
      service.getTestCaseResultHistory('case-1').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1/results?limit=10`);
      expect(req.request.method).toBe('GET');
      req.flush([mockTestResult]);
    });

    it('should return an array of EvalTestResult', () => {
      let result: EvalTestResult[] | undefined;
      service.getTestCaseResultHistory('case-1').subscribe(r => (result = r));

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1/results?limit=10`);
      req.flush([mockTestResult]);

      expect(result).toEqual([mockTestResult]);
    });
  });

  // ─── 16. getLatestSuiteResult() ──────────────────────────────────────────────

  describe('getLatestSuiteResult()', () => {
    it('should GET the latest suite result', () => {
      service.getLatestSuiteResult('suite-1').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/suite-1/results/latest`);
      expect(req.request.method).toBe('GET');
      req.flush(mockSuiteResult);
    });

    it('should return the latest EvalSuiteResult', () => {
      let result: EvalSuiteResult | undefined;
      service.getLatestSuiteResult('suite-1').subscribe(r => (result = r));

      const req = httpMock.expectOne(`${baseUrl}/suite-1/results/latest`);
      req.flush(mockSuiteResult);

      expect(result).toEqual(mockSuiteResult);
    });
  });

  // ─── 17. getLatestTestCaseResult() ───────────────────────────────────────────

  describe('getLatestTestCaseResult()', () => {
    it('should GET the latest test case result', () => {
      service.getLatestTestCaseResult('case-1').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1/results/latest`);
      expect(req.request.method).toBe('GET');
      req.flush(mockTestResult);
    });
  });

  // ─── 18. getFactSheetMetrics() ───────────────────────────────────────────────

  describe('getFactSheetMetrics()', () => {
    it('should GET metrics for a fact sheet', () => {
      service.getFactSheetMetrics(42).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/fact-sheet/42/metrics`);
      expect(req.request.method).toBe('GET');
      req.flush(mockMetrics);
    });

    it('should return FactSheetMetrics', () => {
      let result: FactSheetMetrics | undefined;
      service.getFactSheetMetrics(42).subscribe(m => (result = m));

      const req = httpMock.expectOne(`${baseUrl}/fact-sheet/42/metrics`);
      req.flush(mockMetrics);

      expect(result).toEqual(mockMetrics);
    });
  });

  // ─── 19. getFailingTestCases() ───────────────────────────────────────────────

  describe('getFailingTestCases()', () => {
    it('should GET failing test cases for a fact sheet', () => {
      service.getFailingTestCases(42).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/fact-sheet/42/failing`);
      expect(req.request.method).toBe('GET');
      req.flush([mockCase]);
    });

    it('should return an array of EvalCase', () => {
      let result: EvalCase[] | undefined;
      service.getFailingTestCases(42).subscribe(r => (result = r));

      const req = httpMock.expectOne(`${baseUrl}/fact-sheet/42/failing`);
      req.flush([mockCase]);

      expect(result).toEqual([mockCase]);
    });
  });

  // ─── 20. getTestCasePassRate() ───────────────────────────────────────────────

  describe('getTestCasePassRate()', () => {
    it('should GET pass rate with default windowDays=30', () => {
      service.getTestCasePassRate('case-1').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1/pass-rate?windowDays=30`);
      expect(req.request.method).toBe('GET');
      req.flush(mockPassRate);
    });

    it('should use the provided windowDays parameter', () => {
      service.getTestCasePassRate('case-1', 7).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1/pass-rate?windowDays=7`);
      expect(req.request.method).toBe('GET');
      req.flush(mockPassRate);
    });

    it('should return TestCasePassRate', () => {
      let result: TestCasePassRate | undefined;
      service.getTestCasePassRate('case-1').subscribe(r => (result = r));

      const req = httpMock.expectOne(`${baseUrl}/cases/case-1/pass-rate?windowDays=30`);
      req.flush(mockPassRate);

      expect(result).toEqual(mockPassRate);
    });
  });

  // ─── 21. exportSuite() ───────────────────────────────────────────────────────

  describe('exportSuite()', () => {
    it('should GET the suite export endpoint', () => {
      service.exportSuite('suite-1').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/suite-1/export`);
      expect(req.request.method).toBe('GET');
      req.flush(mockSuite);
    });

    it('should return the exported EvalSuite', () => {
      let result: EvalSuite | undefined;
      service.exportSuite('suite-1').subscribe(s => (result = s));

      const req = httpMock.expectOne(`${baseUrl}/suite-1/export`);
      req.flush(mockSuite);

      expect(result).toEqual(mockSuite);
    });
  });

  // ─── 22. importSuite() ───────────────────────────────────────────────────────

  describe('importSuite()', () => {
    it('should POST to import a suite', () => {
      service.importSuite(mockSuite, 42).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/import`);
      expect(req.request.method).toBe('POST');
      req.flush(mockSuite);
    });

    it('should send suite and targetFactSheetId in body', () => {
      service.importSuite(mockSuite, 42).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/import`);
      expect(req.request.body).toEqual({ suite: mockSuite, targetFactSheetId: 42 });
      req.flush(mockSuite);
    });

    it('should return the imported EvalSuite', () => {
      let result: EvalSuite | undefined;
      service.importSuite(mockSuite, 42).subscribe(s => (result = s));

      const req = httpMock.expectOne(`${baseUrl}/import`);
      req.flush(mockSuite);

      expect(result).toEqual(mockSuite);
    });
  });

  // ─── 23. downloadSuiteAsJson() ───────────────────────────────────────────────

  describe('downloadSuiteAsJson()', () => {
    let createObjectUrlSpy: jasmine.Spy;
    let revokeObjectUrlSpy: jasmine.Spy;
    let originalCreateObjectUrl: typeof URL.createObjectURL;
    let originalRevokeObjectUrl: typeof URL.revokeObjectURL;

    beforeEach(() => {
      originalCreateObjectUrl = URL.createObjectURL;
      originalRevokeObjectUrl = URL.revokeObjectURL;
      createObjectUrlSpy = jasmine.createSpy('createObjectURL').and.returnValue('blob:test-url');
      revokeObjectUrlSpy = jasmine.createSpy('revokeObjectURL');
      (URL as any).createObjectURL = createObjectUrlSpy;
      (URL as any).revokeObjectURL = revokeObjectUrlSpy;
    });

    afterEach(() => {
      (URL as any).createObjectURL = originalCreateObjectUrl;
      (URL as any).revokeObjectURL = originalRevokeObjectUrl;
    });

    it('should call URL.createObjectURL to initiate download', () => {
      service.downloadSuiteAsJson(mockSuite);
      expect(createObjectUrlSpy).toHaveBeenCalled();
    });

    it('should call URL.revokeObjectURL to clean up', () => {
      service.downloadSuiteAsJson(mockSuite);
      expect(revokeObjectUrlSpy).toHaveBeenCalledWith('blob:test-url');
    });
  });

  // ─── 24. parseImportedFile() ─────────────────────────────────────────────────

  describe('parseImportedFile()', () => {
    it('should resolve with parsed JSON from a valid file', async () => {
      const json = JSON.stringify(mockSuite);
      const file = new File([json], 'suite.json', { type: 'application/json' });

      const result = await service.parseImportedFile(file);
      expect(result.id).toBe(mockSuite.id);
      expect(result.name).toBe(mockSuite.name);
    });

    it('should reject with an error for invalid JSON', async () => {
      const file = new File(['not valid json!!!'], 'bad.json', { type: 'application/json' });

      await expectAsync(service.parseImportedFile(file)).toBeRejectedWithError('Invalid JSON file');
    });
  });

  // ─── 25. createEmptyTestCase() ───────────────────────────────────────────────

  describe('createEmptyTestCase()', () => {
    it('should return a CreateTestCaseRequest with default name', () => {
      const tc = service.createEmptyTestCase();
      expect(tc.name).toBe('New Test Case');
    });

    it('should return enabled=true by default', () => {
      const tc = service.createEmptyTestCase();
      expect(tc.enabled).toBeTrue();
    });

    it('should include default evaluation types', () => {
      const tc = service.createEmptyTestCase();
      expect(tc.evaluationTypes).toContain('RELEVANCY');
      expect(tc.evaluationTypes).toContain('FAITHFULNESS');
      expect(tc.evaluationTypes).toContain('ANSWER_CORRECTNESS');
    });

    it('should return empty arrays for facts and entities', () => {
      const tc = service.createEmptyTestCase();
      expect(tc.expectedFacts).toEqual([]);
      expect(tc.forbiddenFacts).toEqual([]);
      expect(tc.expectedEntities).toEqual([]);
    });

    it('should set timeoutMs to 30000', () => {
      const tc = service.createEmptyTestCase();
      expect(tc.timeoutMs).toBe(30000);
    });

    it('should set priority to 3', () => {
      const tc = service.createEmptyTestCase();
      expect(tc.priority).toBe(3);
    });
  });

  // ─── 26. createEmptySuite() ──────────────────────────────────────────────────

  describe('createEmptySuite()', () => {
    it('should return a CreateSuiteRequest with the given factSheetId', () => {
      const suite = service.createEmptySuite(99);
      expect(suite.factSheetId).toBe(99);
    });

    it('should set the default suite name', () => {
      const suite = service.createEmptySuite(1);
      expect(suite.name).toBe('New Evaluation Suite');
    });

    it('should set an empty description', () => {
      const suite = service.createEmptySuite(1);
      expect(suite.description).toBe('');
    });
  });
});
