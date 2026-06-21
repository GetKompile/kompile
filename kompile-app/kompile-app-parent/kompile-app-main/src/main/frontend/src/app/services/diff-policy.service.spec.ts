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
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { DiffPolicyService } from './diff-policy.service';
import { backendUrl } from './base.service';

describe('DiffPolicyService', () => {
  let service: DiffPolicyService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [DiffPolicyService, provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(DiffPolicyService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GET /diff-policy/rules', () => {
    service.getRules().subscribe();
    const req = httpMock.expectOne(`${backendUrl}/diff-policy/rules`);
    expect(req.request.method).toBe('GET');
    req.flush({ pathRules: [], contentRulesText: '' });
  });

  it('PUT /diff-policy/rules sends the rules body', () => {
    service.saveRules({ pathRules: [{ glob: '**/*.env', severity: 'critical', description: 'x' }], contentRulesText: 'r' }).subscribe();
    const req = httpMock.expectOne(`${backendUrl}/diff-policy/rules`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.contentRulesText).toBe('r');
    expect(req.request.body.pathRules.length).toBe(1);
    req.flush({ status: 'saved' });
  });

  it('POST /diff-policy/scan passes scope as query params', () => {
    service.scan({ filePath: '**/*.env', agent: 'codex', since: '2026-01-01T00:00' }).subscribe();
    const req = httpMock.expectOne(r => r.url === `${backendUrl}/diff-policy/scan`);
    expect(req.request.method).toBe('POST');
    expect(req.request.params.get('filePath')).toBe('**/*.env');
    expect(req.request.params.get('agent')).toBe('codex');
    expect(req.request.params.get('since')).toBe('2026-01-01T00:00');
    req.flush({ scannedDiffs: 0, violations: 0 });
  });

  it('GET /diff-policy/violations passes filters (incl. glob filePath)', () => {
    service.listViolations({ filePath: '**/*.env', severity: 'critical', detector: 'path', agent: 'claude-code' }).subscribe();
    const req = httpMock.expectOne(r => r.url === `${backendUrl}/diff-policy/violations`);
    expect(req.request.params.get('filePath')).toBe('**/*.env');
    expect(req.request.params.get('severity')).toBe('critical');
    expect(req.request.params.get('detector')).toBe('path');
    expect(req.request.params.get('agent')).toBe('claude-code');
    req.flush([]);
  });

  it('GET /diff-policy/stats and DELETE /diff-policy/violations', () => {
    service.stats().subscribe();
    httpMock.expectOne(`${backendUrl}/diff-policy/stats`).flush({ total: 0 });

    service.clearViolations().subscribe();
    const del = httpMock.expectOne(`${backendUrl}/diff-policy/violations`);
    expect(del.request.method).toBe('DELETE');
    del.flush({ status: 'cleared' });
  });
});
