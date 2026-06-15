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

import { BenchmarkService, SamediffBenchmarkConfig, SamediffBenchmarkResult, ProfileSearchRequest } from './benchmark.service';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function apiRoot(): string {
  const { protocol, hostname, port } = window.location;
  return `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
}

function makeConfig(overrides: Partial<SamediffBenchmarkConfig> = {}): SamediffBenchmarkConfig {
  return {
    name: 'test-config',
    tritonNumWarps: 8,
    tritonNumStages: 3,
    tritonCacheEnabled: true,
    maxTokens: 100,
    ...overrides
  };
}

function makeResult(overrides: Partial<SamediffBenchmarkResult> = {}): SamediffBenchmarkResult {
  return {
    configName: 'test-config',
    passed: true,
    resetMs: 50,
    compileMs: 200,
    decodeMs: 300,
    validateMs: 10,
    totalMs: 560,
    tokenCount: 100,
    tokPerSec: 178.6,
    decodeTokPerSec: 333.3,
    firstTokenMs: 120,
    tritonLaunches: 100,
    tritonCacheHits: 95,
    finishReason: 'eos',
    timestamp: '2025-01-01T00:00:00Z',
    ...overrides
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('BenchmarkService', () => {
  let service: BenchmarkService;
  let httpMock: HttpTestingController;
  let base: string;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [BenchmarkService]
    });

    service = TestBed.inject(BenchmarkService);
    httpMock = TestBed.inject(HttpTestingController);
    base = `${apiRoot()}/benchmark`;
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. Creation
  // ─────────────────────────────────────────────────────────────────────────────

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. listConfigs()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('listConfigs()', () => {
    it('should GET /benchmark/configs and return array', () => {
      const configs = [makeConfig({ name: 'a' }), makeConfig({ name: 'b' })];

      service.listConfigs().subscribe(result => {
        expect(result.length).toBe(2);
        expect(result[0].name).toBe('a');
      });

      const req = httpMock.expectOne(`${base}/configs`);
      expect(req.request.method).toBe('GET');
      req.flush(configs);
    });

    it('should return empty array when no configs exist', () => {
      service.listConfigs().subscribe(result => {
        expect(result).toEqual([]);
      });

      httpMock.expectOne(`${base}/configs`).flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. getConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getConfig()', () => {
    it('should GET /benchmark/configs/:name', () => {
      const config = makeConfig({ name: 'my-config' });

      service.getConfig('my-config').subscribe(result => {
        expect(result.name).toBe('my-config');
      });

      const req = httpMock.expectOne(`${base}/configs/my-config`);
      expect(req.request.method).toBe('GET');
      req.flush(config);
    });

    it('should propagate 404 error', () => {
      let errorCaught = false;

      service.getConfig('nonexistent').subscribe({
        error: () => { errorCaught = true; }
      });

      httpMock.expectOne(`${base}/configs/nonexistent`).flush(
        { message: 'Not found' }, { status: 404, statusText: 'Not Found' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. saveConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('saveConfig()', () => {
    it('should POST to /benchmark/configs with config body', () => {
      const config = makeConfig({ name: 'new-config' });

      service.saveConfig(config).subscribe(result => {
        expect(result.name).toBe('new-config');
      });

      const req = httpMock.expectOne(`${base}/configs`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(config);
      req.flush(config);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. updateConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('updateConfig()', () => {
    it('should PUT to /benchmark/configs/:name with updated body', () => {
      const config = makeConfig({ name: 'existing', tritonNumWarps: 16 });

      service.updateConfig('existing', config).subscribe(result => {
        expect(result.tritonNumWarps).toBe(16);
      });

      const req = httpMock.expectOne(`${base}/configs/existing`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(config);
      req.flush(config);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. deleteConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('deleteConfig()', () => {
    it('should DELETE /benchmark/configs/:name', () => {
      service.deleteConfig('old-config').subscribe(result => {
        expect(result).toBeTruthy();
      });

      const req = httpMock.expectOne(`${base}/configs/old-config`);
      expect(req.request.method).toBe('DELETE');
      req.flush({ deleted: true });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. activateConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('activateConfig()', () => {
    it('should POST to /benchmark/configs/:name/activate with null body', () => {
      service.activateConfig('my-config').subscribe();

      const req = httpMock.expectOne(`${base}/configs/my-config/activate`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      req.flush({ activated: true });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. getActiveConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getActiveConfig()', () => {
    it('should GET /benchmark/active', () => {
      const active = makeConfig({ name: 'active-config', isActive: true });

      service.getActiveConfig().subscribe(result => {
        expect(result.isActive).toBeTrue();
      });

      const req = httpMock.expectOne(`${base}/active`);
      expect(req.request.method).toBe('GET');
      req.flush(active);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. runBenchmark()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runBenchmark()', () => {
    it('should POST to /benchmark/run with configName query param', () => {
      const result = makeResult({ passed: true });

      service.runBenchmark('my-config').subscribe(r => {
        expect(r.passed).toBeTrue();
        expect(r.decodeTokPerSec).toBe(333.3);
      });

      const req = httpMock.expectOne(r =>
        r.url === `${base}/run` && r.params.get('configName') === 'my-config'
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      req.flush(result);
    });

    it('should return failed result with failureMessage', () => {
      const result = makeResult({ passed: false, failureMessage: 'OOM error' });

      service.runBenchmark('bad-config').subscribe(r => {
        expect(r.passed).toBeFalse();
        expect(r.failureMessage).toBe('OOM error');
      });

      httpMock.expectOne(r => r.url === `${base}/run`).flush(result);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. runMatrix()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runMatrix()', () => {
    it('should POST to /benchmark/run-matrix with request body', () => {
      const request: ProfileSearchRequest = { warpsRange: [4, 8], stagesRange: [2, 3] };
      const results = [makeResult({ configName: 'w4-s2' }), makeResult({ configName: 'w8-s3' })];

      service.runMatrix(request).subscribe(r => {
        expect(r.length).toBe(2);
      });

      const req = httpMock.expectOne(`${base}/run-matrix`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(results);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. searchOptimalProfile()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('searchOptimalProfile()', () => {
    it('should POST to /benchmark/search with search request', () => {
      const request: ProfileSearchRequest = {
        warpsRange: [4, 8, 16],
        stagesRange: [2, 3, 4],
        fpFusionRange: [true, false]
      };
      const response = {
        bestConfig: 'optimal-w8-s3',
        bestResult: makeResult({ configName: 'optimal-w8-s3', decodeTokPerSec: 400 })
      };

      service.searchOptimalProfile(request).subscribe(r => {
        expect(r.bestConfig).toBe('optimal-w8-s3');
        expect(r.bestResult.decodeTokPerSec).toBe(400);
      });

      const req = httpMock.expectOne(`${base}/search`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(response);
    });

    it('should handle response with no bestResult', () => {
      service.searchOptimalProfile({ warpsRange: [4] }).subscribe(r => {
        expect(r.bestResult).toBeUndefined();
      });

      httpMock.expectOne(`${base}/search`).flush({ bestConfig: null });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. getResults()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getResults()', () => {
    it('should GET /benchmark/results', () => {
      const results = [makeResult(), makeResult({ configName: 'b' })];

      service.getResults().subscribe(r => {
        expect(r.length).toBe(2);
      });

      const req = httpMock.expectOne(`${base}/results`);
      expect(req.request.method).toBe('GET');
      req.flush(results);
    });

    it('should return empty array', () => {
      service.getResults().subscribe(r => {
        expect(r).toEqual([]);
      });

      httpMock.expectOne(`${base}/results`).flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 13. clearResults()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('clearResults()', () => {
    it('should DELETE /benchmark/results', () => {
      service.clearResults().subscribe();

      const req = httpMock.expectOne(`${base}/results`);
      expect(req.request.method).toBe('DELETE');
      req.flush({ cleared: true });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 14. applyOptimalDefaults()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('applyOptimalDefaults()', () => {
    it('should POST to /benchmark/apply-optimal with null body', () => {
      service.applyOptimalDefaults().subscribe();

      const req = httpMock.expectOne(`${base}/apply-optimal`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      req.flush({ applied: true });
    });
  });
});
