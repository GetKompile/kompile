import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot } from '@angular/router';
import { ElementRef } from '@angular/core';
import { of, Subject } from 'rxjs';
import { ProductChatContextService, productChatRouteGuard, readProductChatDescriptor } from './product-chat-context.service';
import { ProductChatPageComponent } from './product-chat-page.component';

const descriptor = () => ({ enabled: true, productId: 'elthoria-play', title: 'Elthoria',
  workingDirectory: '/approved/play', agentName: 'role:elthoria-gm', chat: { id: 'chat-one', name: 'Play' },
  panelsUrl: '/api/agents/chat/product/assets/index.html', domainBaseUrl: '/api/agents/chat/product/domain', status: 'STOPPED' });

describe('Product chat discovery and route boundary', () => {
  it('preserves ordinary mode only for an explicitly disabled descriptor', () => {
    expect(readProductChatDescriptor({ enabled: false }, location.origin)).toBeNull();
    expect(() => readProductChatDescriptor({}, location.origin)).toThrow();
  });
  it('freezes host identity and rejects foreign, encoded and alternate resources', () => {
    const source = descriptor();
    const copy = readProductChatDescriptor(source, location.origin)!;
    source.chat.id = 'other';
    expect(copy.chat.id).toBe('chat-one');
    expect(Object.isFrozen(copy.chat)).toBeTrue();
    for (const panelsUrl of ['https://foreign.invalid/panel', '//foreign.invalid/panel',
      '/api/agents/chat/product/assets/../index.html', '/api/agents/chat/product/assets/%69ndex.html',
      '/api/agents/chat/product/assets/index.html?secret=bad']) {
      expect(() => readProductChatDescriptor({ ...descriptor(), panelsUrl }, location.origin)).toThrow();
    }
    expect(() => readProductChatDescriptor({ ...descriptor(), agentName: 'coder' }, location.origin)).toThrow();
  });
  it('shares one cold discovery read and accepts old-server 404 without retrying', async () => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    const service = TestBed.inject(ProductChatContextService);
    const http = TestBed.inject(HttpTestingController);
    http.expectNone(() => true);
    const first = service.load();
    const second = service.load();
    expect(first).toBe(second);
    http.expectOne(request => request.url.endsWith('/agents/chat/product')).flush({}, { status: 404, statusText: 'Not found' });
    expect(await first).toBeNull();
    expect(await service.load()).toBeNull();
    http.verify();
  });
  it('does not fall through to generic management on a broken product response', async () => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    const service = TestBed.inject(ProductChatContextService);
    const http = TestBed.inject(HttpTestingController);
    const promise = service.load();
    http.expectOne(request => request.url.endsWith('/agents/chat/product')).flush({ enabled: true });
    await expectAsync(promise).toBeRejected();
    http.verify();
  });
  it('redirects managed generic routes, and rejects the play route in ordinary mode', async () => {
    let enabled = true;
    const router = { createUrlTree: jasmine.createSpy('tree').and.callFake((paths: string[]) => paths[0]) };
    TestBed.configureTestingModule({ providers: [
      { provide: ProductChatContextService, useValue: { load: () => Promise.resolve(enabled ? descriptor() : null) } },
      { provide: Router, useValue: router }
    ] });
    const guard = (path: string) => TestBed.runInInjectionContext(() => productChatRouteGuard(
      { routeConfig: { path } } as ActivatedRouteSnapshot, {} as RouterStateSnapshot));
    expect(await guard('settings')).toBe('/play' as any);
    expect(await guard('play')).toBeTrue();
    enabled = false;
    expect(await guard('play')).toBe('/chat' as any);
    expect(await guard('chat')).toBeTrue();
  });
});

describe('Product chat page play-authority attachment', () => {
  let page: ProductChatPageComponent;
  let postMessage: jasmine.Spy;
  let http: any;
  beforeEach(async () => {
    http = { post: jasmine.createSpy('post').and.returnValue(of({})),
      get: jasmine.createSpy('get').and.callFake((url: string) => of(url.endsWith('/content-approvals')
        ? [] : { status: 'READY', runtimeId: 'runtime-one' })),
      delete: jasmine.createSpy('delete').and.returnValue(of({ status: 'closed' })) };
    page = new ProductChatPageComponent({ load: () => Promise.resolve(readProductChatDescriptor(descriptor(), location.origin)) } as any,
      http, { bypassSecurityTrustResourceUrl: (url: string) => url } as any);
    await page.ngOnInit();
    postMessage = jasmine.createSpy('postMessage');
    page.panels = new ElementRef({ contentWindow: { postMessage } } as any);
    page.panelLoaded = true;
  });
  afterEach(() => page.ngOnDestroy());
  it('does not start a session or model during descriptor and panel initialization', () => {
    expect(http.post).not.toHaveBeenCalled();
    expect(http.get).not.toHaveBeenCalled();
    expect(page.sessionOpen).toBeFalse();
  });
  it('opens once and pins panel traffic to the confirmed runtime generation', async () => {
    await page.openSession();
    expect(page.sessionOpen).toBeTrue();
    expect(http.get).toHaveBeenCalledWith(jasmine.stringMatching('/content-approvals$'));
    expect(page.approvals).toEqual([]);
    expect(http.post).toHaveBeenCalledTimes(1); // Metadata refresh never submits a generation.
    page.campaignId = 7; page.simulationId = 9; page.savedGridId = 3;
    page.attachPanels();
    const attach = postMessage.calls.mostRecent().args[0];
    expect(attach.apiBaseUrl).toBe('/api/agents/chat/product/domain/runtime-one');
    expect(attach.scope).toEqual({ campaignId: 7, simulationId: 9, savedGridId: 3 });
    await page.refreshPanels();
    const refresh = postMessage.calls.mostRecent().args[0];
    expect(refresh.type).toBe('refresh');
    expect(refresh.generation).toBeGreaterThan(attach.generation);
    expect(refresh.requestId).not.toBe(attach.requestId);
    expect(refresh.apiBaseUrl).toBeUndefined();
  });
  it('permits explicit closure even when a quarantined runtime cannot be opened', async () => {
    expect(page.sessionOpen).toBeFalse();
    await page.closeSession();
    expect(http.delete).toHaveBeenCalledTimes(1);
    expect(http.post).not.toHaveBeenCalled();
    expect(page.status).toContain('Play session closed');
  });
  it('does not mistake HTTP 200 for proven resource closure', async () => {
    await page.openSession();
    http.delete.and.returnValue(of({ status: 'failed' }));
    await page.closeSession();
    expect(page.sessionOpen).toBeFalse(); // View detached, not a claim that the server exited.
    expect(page.error).toContain('Closure was not confirmed');
    expect(page.status).not.toContain('Play session closed');
  });
  it('rejects invalid selection and never interprets narrative text as state', async () => {
    await page.openSession();
    page.campaignId = 1.5; page.attachPanels();
    expect(postMessage).not.toHaveBeenCalled();
    (page as any).receivePanel({ origin: location.origin, source: {}, data: { type: 'ready', protocol: 'elthoria.play-panels.v1' } });
    expect(postMessage).not.toHaveBeenCalled();
  });
  it('clears attachment after a changed runtime rather than replaying a command', async () => {
    await page.openSession(); page.campaignId = 7; page.attachPanels();
    http.get.and.returnValue(of({ status: 'READY', runtimeId: 'runtime-two' }));
    await page.refreshPanels();
    expect(postMessage.calls.mostRecent().args[0].type).toBe('disconnect');
    expect(page.error).toContain('runtime changed');
    expect(http.post).toHaveBeenCalledTimes(1);
  });
  it('detaches the browser without closing the server and ignores late bootstrap callbacks', async () => {
    const pending = new Subject();
    http.post.and.returnValue(pending);
    const opening = page.openSession();
    page.ngOnDestroy(); pending.next({}); pending.complete();
    await opening;
    expect(page.sessionOpen).toBeFalse();
    expect(http.delete).not.toHaveBeenCalled();
  });
});
