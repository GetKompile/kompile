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

import { HttpClientTestingModule, HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ServiceEndpointRouter } from '@shared/services/service-endpoint-routing';

import { ToolCallCatalogComponent } from './tool-call-catalog.component';

/** Empty but valid bodies for the three requests the panel sends on load, keyed by URL suffix. */
const EMPTY_BODIES: Record<string, object> = {
  '/tool-calls': { results: [], totalCount: 0, page: 0, pageSize: 50, totalPages: 0 },
  '/tool-calls/stats': {
    totalToolCalls: 0, totalErrors: 0, sessionCount: 0,
    byTool: {}, byCategory: {}, byAgent: {}, bySource: {}, byProject: {}
  },
  '/tool-calls/filters': { toolNames: [], categories: [], agents: [], sources: [], sessions: [], projects: [] }
};

describe('ToolCallCatalogComponent', () => {
  let fixture: ComponentFixture<ToolCallCatalogComponent>;
  let http: HttpTestingController;

  function render(chatReachable: boolean): void {
    const router = jasmine.createSpyObj<ServiceEndpointRouter>(
      'ServiceEndpointRouter', ['isReachable', 'dependencyStatus']);
    router.isReachable.and.returnValue(chatReachable);
    router.dependencyStatus.and.returnValue({
      dependency: 'chat',
      configured: true,
      endpointUrl: 'http://chat.example:8081',
      reachable: chatReachable,
      statusCode: chatReachable ? 200 : 0
    });

    TestBed.configureTestingModule({
      imports: [ToolCallCatalogComponent, HttpClientTestingModule, NoopAnimationsModule],
      providers: [{ provide: ServiceEndpointRouter, useValue: router }]
    });
    fixture = TestBed.createComponent(ToolCallCatalogComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  }

  const catalogRequests = (): TestRequest[] => http.match(req => req.url.includes('/tool-calls'));
  const isSearch = (req: TestRequest): boolean => req.request.url.endsWith('/tool-calls');
  const flushEmpty = (requests: TestRequest[]): void => requests.forEach(req =>
    req.flush(EMPTY_BODIES[Object.keys(EMPTY_BODIES).find(suffix => req.request.url.endsWith(suffix))!]));
  const page = (): string => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const banner = (): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector('.catalog-error');

  afterEach(() => http.verify());

  it('explains an unreachable chat app instead of listing no tool calls', () => {
    render(false);

    expect(catalogRequests().length).toBe(0);
    expect(banner()?.textContent).toContain('Chat app at http://chat.example:8081, which could not be reached');
    expect(page()).not.toContain('No tool calls found');
    expect(page()).not.toContain('tool call(s) found');
  });

  it('shows the server error message instead of the empty state', () => {
    render(true);
    const requests = catalogRequests();
    expect(requests.length).toBe(3);

    requests.find(isSearch)!.flush(
      { error: 'Internal server error', message: 'catalog index is corrupt' },
      { status: 500, statusText: 'Internal Server Error' });
    flushEmpty(requests.filter(req => !isSearch(req)));
    fixture.detectChanges();

    expect(banner()?.textContent).toContain('Loading tool calls failed (HTTP 500): catalog index is corrupt');
    expect(page()).not.toContain('No tool calls found');
  });

  it('treats a request that got no response as the chat app being unreachable', () => {
    render(true);

    catalogRequests().forEach(req => req.error(new ProgressEvent('error')));
    fixture.detectChanges();

    expect(banner()?.textContent).toContain('which could not be reached');
  });

  it('retries every request and clears the banner once the chat app answers', () => {
    render(false);

    banner()!.querySelector('button')!.click();
    const requests = catalogRequests();
    expect(requests.length).toBe(3);
    flushEmpty(requests);
    fixture.detectChanges();

    expect(banner()).toBeNull();
    expect(page()).toContain('No tool calls found');
  });
});
