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

import { GitDiffService } from './git-diff.service';
import { backendUrl } from './base.service';

describe('GitDiffService', () => {
  let service: GitDiffService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [GitDiffService, provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(GitDiffService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('GET /git/status', () => {
    service.status().subscribe(s => expect(s.branch).toBe('main'));
    const req = httpMock.expectOne(`${backendUrl}/git/status`);
    expect(req.request.method).toBe('GET');
    req.flush({ repo: true, root: '/repo', branch: 'main' });
  });

  it('GET /git/branches', () => {
    service.branches().subscribe(b => expect(b).toEqual(['main', 'dev']));
    httpMock.expectOne(`${backendUrl}/git/branches`).flush(['main', 'dev']);
  });

  it('GET /git/commits passes every filter as a query param', () => {
    service.commits({
      branch: 'dev', limit: 25, since: '2026-01-01T00:00',
      until: '2026-02-01T00:00', path: 'Foo.java', query: 'fix'
    }).subscribe();

    const req = httpMock.expectOne(r => r.url === `${backendUrl}/git/commits`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('branch')).toBe('dev');
    expect(req.request.params.get('limit')).toBe('25');
    expect(req.request.params.get('since')).toBe('2026-01-01T00:00');
    expect(req.request.params.get('until')).toBe('2026-02-01T00:00');
    expect(req.request.params.get('path')).toBe('Foo.java');
    expect(req.request.params.get('query')).toBe('fix');
    req.flush([]);
  });

  it('GET /git/commits omits empty filters', () => {
    service.commits({}).subscribe();
    const req = httpMock.expectOne(r => r.url === `${backendUrl}/git/commits`);
    expect(req.request.params.keys().length).toBe(0);
    req.flush([]);
  });

  it('GET /git/commits/{hash}/diff encodes the hash', () => {
    service.commitDiff('abc123').subscribe();
    const req = httpMock.expectOne(`${backendUrl}/git/commits/abc123/diff`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('GET /git/file-history with path + bounds', () => {
    service.fileHistory('src/Foo.java', { limit: 10, since: '2026-01-01', until: '2026-03-01' }).subscribe();
    const req = httpMock.expectOne(r => r.url === `${backendUrl}/git/file-history`);
    expect(req.request.params.get('path')).toBe('src/Foo.java');
    expect(req.request.params.get('limit')).toBe('10');
    expect(req.request.params.get('since')).toBe('2026-01-01');
    expect(req.request.params.get('until')).toBe('2026-03-01');
    req.flush([]);
  });

  it('GET /git/file-history omits bounds when not supplied', () => {
    service.fileHistory('src/Foo.java').subscribe();
    const req = httpMock.expectOne(r => r.url === `${backendUrl}/git/file-history`);
    expect(req.request.params.get('path')).toBe('src/Foo.java');
    expect(req.request.params.has('since')).toBeFalse();
    expect(req.request.params.has('limit')).toBeFalse();
    req.flush([]);
  });

  it('GET /git/file passes ref + path', () => {
    service.fileAtRef('HEAD', 'src/Foo.java').subscribe(r => expect(r.content).toBe('x'));
    const req = httpMock.expectOne(r => r.url === `${backendUrl}/git/file`);
    expect(req.request.params.get('ref')).toBe('HEAD');
    expect(req.request.params.get('path')).toBe('src/Foo.java');
    req.flush({ ref: 'HEAD', path: 'src/Foo.java', content: 'x' });
  });
});
