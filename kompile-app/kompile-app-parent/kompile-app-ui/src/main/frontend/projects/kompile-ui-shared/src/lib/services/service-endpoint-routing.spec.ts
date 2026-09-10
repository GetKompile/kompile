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

import { TestBed } from '@angular/core/testing';
import {
  HTTP_INTERCEPTORS,
  HttpClient
} from '@angular/common/http';
import {
  HttpClientTestingModule,
  HttpTestingController
} from '@angular/common/http/testing';

import {
  ManagedServiceEndpoints,
  ServiceEndpointRouter,
  ServiceEndpointRoutingInterceptor
} from './service-endpoint-routing';

describe('ServiceEndpointRouter', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  let router: ServiceEndpointRouter;

  const endpoints: ManagedServiceEndpoints = {
    adminUrl: 'http://localhost:9380',
    chatUrl: 'http://localhost:9381',
    crawlUrl: 'http://localhost:9382',
    stagingUrl: 'http://localhost:9390',
    servingUrl: 'http://127.0.0.1:8091',
    routes: {
      '/api/staging-config': 'admin',
      '/api/embedding-restart': 'admin',
      '/api/models/active-context': 'admin',
      '/api/chat': 'chat',
      '/api/cross-index': 'crawl',
      '/api/unified-crawl': 'crawl'
    }
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        ServiceEndpointRouter,
        {
          provide: HTTP_INTERCEPTORS,
          useClass: ServiceEndpointRoutingInterceptor,
          multi: true
        }
      ]
    });

    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(ServiceEndpointRouter);
  });

  afterEach(() => {
    http.verify();
    sessionStorage.removeItem('kompile.channel.csrf');
  });

  async function load(
    config: ManagedServiceEndpoints = endpoints,
    reachability: Partial<Record<'admin' | 'chat' | 'crawl', boolean>> = {}
  ): Promise<void> {
    const pending = router.load();
    http.expectOne('/api/service-endpoints').flush(config);
    await Promise.resolve();
    for (const persona of ['admin', 'chat', 'crawl'] as const) {
      const reachable = reachability[persona] ?? true;
      http.expectOne(`/api/service-endpoints/dependencies/${persona}`).flush({
        dependency: persona,
        configured: true,
        endpointUrl: config[`${persona}Url`],
        reachable,
        statusCode: reachable ? 200 : 0
      });
    }
    await pending;
  }

  it('loads managed endpoints without routing bootstrap or dependency-health requests', async () => {
    await load();

    expect(router.resolve('/api/service-endpoints')).toBe('/api/service-endpoints');
    expect(router.resolve('/api/service-endpoints/dependencies/admin'))
      .toBe('/api/service-endpoints/dependencies/admin');
  });

  it('records explicit managed dependency reachability for optional feature gating', async () => {
    await load(endpoints, { admin: false, crawl: false });

    expect(router.isReachable('admin')).toBeFalse();
    expect(router.isReachable('chat')).toBeTrue();
    expect(router.isReachable('crawl')).toBeFalse();
    expect(router.dependencyStatus('admin')?.endpointUrl).toBe(endpoints.adminUrl);
  });

  it('routes explicitly owned APIs to configured custom ports', async () => {
    await load();

    expect(router.resolve('/api/staging-config/configs/active'))
      .toBe('http://localhost:9380/api/staging-config/configs/active');
    expect(router.resolve('/api/chat/sessions?limit=5'))
      .toBe('http://localhost:9381/api/chat/sessions?limit=5');
    expect(router.resolve('/api/cross-index/status/1'))
      .toBe('http://localhost:9382/api/cross-index/status/1');
  });

  it('uses longest-prefix ownership and preserves shared same-origin APIs', async () => {
    await load({
      ...endpoints,
      routes: {
        '/api/models': 'chat',
        '/api/models/active-context': 'admin'
      }
    });

    expect(router.resolve('/api/models/active-context'))
      .toBe('http://localhost:9380/api/models/active-context');
    expect(router.resolve('/api/models/catalog'))
      .toBe('http://localhost:9381/api/models/catalog');
    expect(router.resolve('/api/fact-sheets')).toBe('/api/fact-sheets');
  });

  it('does not rewrite external or non-API URLs', async () => {
    await load();

    expect(router.resolve('https://example.com/api/chat')).toBe('https://example.com/api/chat');
    expect(router.resolve('/assets/branding/kompile-logo.svg'))
      .toBe('/assets/branding/kompile-logo.svg');
    expect(router.resolve('/api/channel-integrations/browser-sessions/exchange'))
      .toBe('/api/channel-integrations/browser-sessions/exchange');
  });

  it('intercepts HttpClient requests after topology initialization', async () => {
    await load();

    client.get('/api/staging-config/configs/active').subscribe();
    const routed = http.expectOne('http://localhost:9380/api/staging-config/configs/active');
    expect(routed.request.url).toBe('http://localhost:9380/api/staging-config/configs/active');
    routed.flush(null);

    client.get('/api/fact-sheets').subscribe();
    const sameOrigin = http.expectOne('/api/fact-sheets');
    expect(sameOrigin.request.url).toBe('/api/fact-sheets');
    sameOrigin.flush([]);
  });

  it('carries the integration session and CSRF proof to remote crawl mutations', async () => {
    await load();
    sessionStorage.setItem('kompile.channel.csrf', 'crawl-csrf');

    client.post('/api/unified-crawl/start', {}).subscribe();
    const request = http.expectOne('http://localhost:9382/api/unified-crawl/start');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('X-Kompile-Channel-Request')).toBe('1');
    expect(request.request.headers.get('X-Kompile-Channel-CSRF')).toBe('crawl-csrf');
    request.flush({ jobId: 'job-1' });
  });

  it('falls back to same-origin requests when topology loading fails', async () => {
    const pending = router.load();
    http.expectOne('/api/service-endpoints').flush(
      { error: 'unavailable' },
      { status: 503, statusText: 'Unavailable' }
    );
    await pending;

    expect(router.resolve('/api/staging-config/configs/active'))
      .toBe('/api/staging-config/configs/active');
    expect(router.isReachable('admin')).toBeTrue();
  });
});
