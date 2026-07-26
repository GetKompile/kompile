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

import { DiffIndexService } from './diff-index.service';
import { backendUrl } from './base.service';

/** Covers the session endpoints + the since/until time filter added for diff browsing. */
describe('DiffIndexService (sessions + time filter)', () => {
  let service: DiffIndexService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [DiffIndexService, provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(DiffIndexService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GET /diff-index/sessions', () => {
    service.listSessions().subscribe();
    const req = httpMock.expectOne(`${backendUrl}/diff-index/sessions`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('GET /diff-index/sessions/{id} url-encodes the session id', () => {
    service.sessionEntries('sess/A 1').subscribe();
    const req = httpMock.expectOne(`${backendUrl}/diff-index/sessions/sess%2FA%201`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('search forwards since/until (and file) as query params', () => {
    service.search({ filePath: 'Foo.java', since: '2026-01-01T00:00', until: '2026-02-01T00:00' }).subscribe();
    const req = httpMock.expectOne(r => r.url === `${backendUrl}/diff-index/search`);
    expect(req.request.params.get('filePath')).toBe('Foo.java');
    expect(req.request.params.get('since')).toBe('2026-01-01T00:00');
    expect(req.request.params.get('until')).toBe('2026-02-01T00:00');
    req.flush([]);
  });

  it('search omits since/until when not supplied', () => {
    service.search({ agent: 'codex' }).subscribe();
    const req = httpMock.expectOne(r => r.url === `${backendUrl}/diff-index/search`);
    expect(req.request.params.get('agent')).toBe('codex');
    expect(req.request.params.has('since')).toBeFalse();
    expect(req.request.params.has('until')).toBeFalse();
    req.flush([]);
  });
});
