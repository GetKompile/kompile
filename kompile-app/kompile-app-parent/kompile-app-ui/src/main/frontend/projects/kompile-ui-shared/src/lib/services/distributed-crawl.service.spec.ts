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

import { DistributedCrawlService } from './distributed-crawl.service';

describe('DistributedCrawlService', () => {
  let service: DistributedCrawlService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [DistributedCrawlService]
    });
    service = TestBed.inject(DistributedCrawlService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('listSessions() GETs the sessions endpoint', () => {
    service.listSessions().subscribe();
    const req = httpMock.expectOne(r => r.url.endsWith('/distributed-crawl/sessions'));
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('getAggregate() GETs the session with aggregate=true', () => {
    service.getAggregate('sess-1').subscribe();
    const req = httpMock.expectOne(r => r.url.endsWith('/distributed-crawl/sessions/sess-1'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('aggregate')).toBe('true');
    req.flush({});
  });

  it('cancelSession() POSTs the cancel endpoint', () => {
    service.cancelSession('sess-1').subscribe();
    const req = httpMock.expectOne(r => r.url.endsWith('/distributed-crawl/sessions/sess-1/cancel'));
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('distributedEventsStreamUrl() builds the per-session SSE id', () => {
    expect(service.distributedEventsStreamUrl('sess-1'))
      .toContain('/crawl-events/stream/distributed-sess-1');
  });
});
