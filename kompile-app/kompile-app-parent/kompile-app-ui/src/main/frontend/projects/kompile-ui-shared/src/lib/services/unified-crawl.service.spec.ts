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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import { HttpClient } from '@angular/common/http';

import { ServiceEndpointRouter } from './service-endpoint-routing';
import { UnifiedCrawlService } from './unified-crawl.service';

describe('UnifiedCrawlService split-persona stream routing', () => {
  let endpointRouter: jasmine.SpyObj<ServiceEndpointRouter>;
  let service: UnifiedCrawlService;

  beforeEach(() => {
    endpointRouter = jasmine.createSpyObj<ServiceEndpointRouter>(
      'ServiceEndpointRouter',
      ['resolve']
    );
    service = new UnifiedCrawlService({} as HttpClient, endpointRouter);
  });

  it('routes the global raw EventSource URL through the managed crawl owner', () => {
    endpointRouter.resolve.and.returnValue(
      'http://crawl.example:8082/api/crawl-events/stream'
    );

    expect(service.crawlEventsStreamUrl())
      .toBe('http://crawl.example:8082/api/crawl-events/stream');
    expect(endpointRouter.resolve)
      .toHaveBeenCalledOnceWith(`${window.location.origin}/api/crawl-events/stream`);
  });

  it('routes a job raw EventSource URL through the managed crawl owner', () => {
    endpointRouter.resolve.and.returnValue(
      'http://crawl.example:8082/api/crawl-events/stream/job-123'
    );

    expect(service.jobEventStreamUrl('job-123'))
      .toBe('http://crawl.example:8082/api/crawl-events/stream/job-123');
    expect(endpointRouter.resolve)
      .toHaveBeenCalledOnceWith(`${window.location.origin}/api/crawl-events/stream/job-123`);
  });
});
