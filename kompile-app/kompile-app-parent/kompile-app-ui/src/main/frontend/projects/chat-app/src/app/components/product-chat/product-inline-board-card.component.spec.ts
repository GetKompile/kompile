import { CommonModule } from '@angular/common';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { backendUrl } from '@shared/services/base.service';
import { ToolPresentation } from '@shared/models/api-models';
import { ProductInlineBoardCardComponent } from './product-inline-board-card.component';

const endpoint = `${backendUrl}/agents/chat/product`;
const runtime = '12345678-1234-1234-1234-123456789abc';
const envelope = (): ToolPresentation => ({ version: 1, kind: 'elthoria.tactical-board',
  data: { campaignId: 3, savedGridId: 7, runtimeId: runtime, observedRevision: 'r-current-1' },
  source: { serverId: 'elthoria', toolName: 'show_tactical_board' },
  identity: { sessionId: 'chat-1', turnId: '0123456789abcdef0123456789abcdef', callId: 'call-1' } });
const protocol = 'elthoria.play-panels.v1';

describe('ProductInlineBoardCardComponent real template and isolated read-only bridge', () => {
  let fixture: ComponentFixture<ProductInlineBoardCardComponent>;
  let requests: HttpTestingController;
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [CommonModule, HttpClientTestingModule],
      declarations: [ProductInlineBoardCardComponent] }).compileComponents();
    requests = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProductInlineBoardCardComponent);
    fixture.componentRef.setInput('sessionId', 'chat-1');
    fixture.componentRef.setInput('presentation', envelope());
  });
  afterEach(() => { fixture.destroy(); requests.verify({ ignoreCancelled: true }); });
  const root = () => fixture.nativeElement as HTMLElement;
  function idle(): HTMLIFrameElement {
    fixture.componentRef.setInput('runIdle', true); fixture.detectChanges();
    requests.expectOne(endpoint + '/connection').flush({ status: 'READY', runtimeId: runtime });
    fixture.detectChanges();
    return root().querySelector('iframe')!;
  }
  function message(frame: HTMLIFrameElement, data: unknown, origin = window.location.origin): void {
    window.dispatchEvent(new MessageEvent('message', { origin, source: frame.contentWindow, data }));
  }
  function attach(frame: HTMLIFrameElement): { post: jasmine.Spy; payload: any } {
    const post = spyOn(frame.contentWindow!, 'postMessage');
    frame.dispatchEvent(new Event('load'));
    message(frame, { protocol, type: 'ready' });
    return { post, payload: post.calls.mostRecent().args[0] };
  }

  it('waits for idle then attaches the exact fixed fragment asset and scoped owned domain only', () => {
    fixture.detectChanges(); requests.expectNone(endpoint + '/connection');
    expect(root().querySelector('iframe')).toBeNull();
    const frame = idle();
    expect(frame.getAttribute('src')).toBe(endpoint + '/assets/index.html#inline-board');
    expect(new URL(frame.src).search).toBe('');
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts allow-same-origin');
    const { post, payload } = attach(frame);
    expect(payload).toEqual({ protocol, type: 'attach', requestId: jasmine.any(String), generation: jasmine.any(Number),
      runtimeId: runtime, scope: { campaignId: 3, savedGridId: 7 }, apiBaseUrl: endpoint + '/domain/' + runtime });
    expect(post.calls.mostRecent().args[1]).toBe(window.location.origin);
    expect(root().textContent).toContain('Live / read-only');
    expect(root().textContent).toContain('r-current-1');
    expect(root().textContent).toContain('not a historical snapshot');
    message(frame, { protocol, type: 'ready' });
    expect(post).toHaveBeenCalledTimes(1); // handshake replay is not an automatic retry
    requests.expectNone(request => request.method !== 'GET');
  });

  it('rejects unknown kinds, rogue capabilities, unsafe IDs, mismatched source and identity before any read', () => {
    for (const change of [
      (p: any) => p.kind = 'unknown', (p: any) => p.version = 2,
      (p: any) => p.data.url = 'https://rogue.invalid', (p: any) => p.url = '/rogue',
      (p: any) => p.data.campaignId = 0, (p: any) => p.data.savedGridId = Number.MAX_SAFE_INTEGER + 1,
      (p: any) => p.data.runtimeId = 'other-runtime', (p: any) => p.data.observedRevision = ' bad ',
      (p: any) => p.source.serverId = 'other', (p: any) => p.source.toolName = 'move',
      (p: any) => p.identity.sessionId = 'other', (p: any) => p.identity.turnId = 'bad'
    ]) {
      const value = envelope(); change(value);
      fixture.componentRef.setInput('presentation', value);
      fixture.componentRef.setInput('runIdle', true); fixture.detectChanges();
      expect(root().querySelector('iframe')).withContext(change.toString()).toBeNull();
      requests.expectNone(endpoint + '/connection');
    }
  });

  it('does not bind a new, unready, quarantined or denied runtime and never retries mutations', () => {
    fixture.componentRef.setInput('runIdle', true); fixture.detectChanges();
    requests.expectOne(endpoint + '/connection').flush({ status: 'READY', runtimeId: '12345678-1234-1234-1234-123456789abd' });
    fixture.detectChanges(); expect(root().querySelector('iframe')).toBeNull();
    for (const status of ['BUSY', 'QUARANTINED', 'CLOSING']) {
      fixture.componentInstance.refresh();
      requests.expectOne(endpoint + '/connection').flush({ status, runtimeId: runtime });
      fixture.detectChanges(); expect(root().querySelector('iframe')).toBeNull();
    }
    fixture.componentInstance.refresh();
    requests.expectOne(endpoint + '/connection').flush({}, { status: 403, statusText: 'Denied' });
    fixture.detectChanges(); expect(root().textContent).toContain('unavailable');
    requests.expectNone(() => true);
  });

  it('waits for document load plus ready and rechecks ownership on same-window navigation', () => {
    const frame = idle();
    message(frame, { protocol, type: 'ready' });
    const post = spyOn(frame.contentWindow!, 'postMessage');
    frame.dispatchEvent(new Event('load'));
    expect(post.calls.mostRecent().args[0].type).toBe('attach');
    frame.dispatchEvent(new Event('load'));
    requests.expectOne(endpoint + '/connection').flush({ status: 'READY', runtimeId: '12345678-1234-1234-1234-123456789abd' });
    fixture.detectChanges();
    expect(root().querySelector('iframe')).toBeNull();
    expect(fixture.componentInstance.status).toContain('unavailable');
  });

  it('does not cross-deliver between two cards even when both board bindings are identical', () => {
    // Karma never fires the sandboxed 404 load, so each card needs the production load
    // event plus one genuine ready before its single attach. Cross-delivery stays locked.
    const first = idle();
    const firstPost = spyOn(first.contentWindow!, 'postMessage');
    first.dispatchEvent(new Event('load'));
    message(first, { protocol, type: 'ready' });
    expect(firstPost.calls.count()).toBe(1);
    expect(firstPost.calls.mostRecent().args[0].type).toBe('attach');
    const secondFixture = TestBed.createComponent(ProductInlineBoardCardComponent);
    try {
      secondFixture.componentRef.setInput('sessionId', 'chat-1');
      secondFixture.componentRef.setInput('presentation', envelope());
      secondFixture.componentRef.setInput('runIdle', true); secondFixture.detectChanges();
      requests.expectOne(endpoint + '/connection').flush({ status: 'READY', runtimeId: runtime }); secondFixture.detectChanges();
      const second = secondFixture.nativeElement.querySelector('iframe') as HTMLIFrameElement;
      const secondPost = spyOn(second.contentWindow!, 'postMessage');
      second.dispatchEvent(new Event('load'));
      message(second, { protocol, type: 'ready' });
      expect(secondPost.calls.count()).toBe(1);
      const firstPayload = firstPost.calls.mostRecent().args[0];
      const secondPayload = secondPost.calls.mostRecent().args[0];
      expect(secondPayload.requestId).not.toBe(firstPayload.requestId);
      expect(secondPayload.scope).toEqual({ campaignId: 3, savedGridId: 7 });
    } finally { secondFixture.destroy(); }
  });

  it('gates messages by origin, window, request, generation, scope and runtime', () => {
    const frame = idle(); const { payload } = attach(frame);
    const ready = { ...payload, type: 'status', status: 'ready' };
    const before = fixture.componentInstance.status;
    message(frame, ready, 'https://rogue.invalid');
    window.dispatchEvent(new MessageEvent('message', { origin: window.location.origin, source: window, data: ready }));
    for (const patch of [{ requestId: 'other' }, { generation: payload.generation - 1 },
      { runtimeId: 'other' }, { scope: { campaignId: 3, savedGridId: 8 } }]) message(frame, { ...ready, ...patch });
    expect(fixture.componentInstance.status).toBe(before);
    message(frame, ready); expect(fixture.componentInstance.status).toContain('loaded');
    message(frame, { ...ready, type: 'error', code: 'read-failed' }); fixture.detectChanges();
    expect(root().querySelector('iframe')).toBeNull(); requests.expectNone(() => true);
  });

  it('cancels local reads on busy/replacement/destroy, rejects stale iframe messages and refreshes only reads', () => {
    fixture.componentRef.setInput('runIdle', true); fixture.detectChanges();
    const pending = requests.expectOne(endpoint + '/connection');
    fixture.componentRef.setInput('runIdle', false); fixture.detectChanges();
    expect(pending.cancelled).toBeTrue();
    const oldFrame = idle(), oldWindow = oldFrame.contentWindow;
    const { post, payload } = attach(oldFrame);
    fixture.componentRef.setInput('refreshEpoch', 1); fixture.detectChanges();
    expect(post.calls.mostRecent().args[0].type).toBe('disconnect');
    requests.expectOne(endpoint + '/connection').flush({ status: 'READY', runtimeId: runtime }); fixture.detectChanges();
    const newFrame = root().querySelector('iframe')!;
    expect(newFrame).not.toBe(oldFrame);
    const newPost = spyOn(newFrame.contentWindow!, 'postMessage');
    window.dispatchEvent(new MessageEvent('message', { origin: window.location.origin, source: oldWindow,
      data: { protocol, type: 'ready' } }));
    window.dispatchEvent(new MessageEvent('message', { origin: window.location.origin, source: oldWindow,
      data: { ...payload, type: 'status', status: 'ready' } }));
    expect(newPost).not.toHaveBeenCalled();
    newFrame.dispatchEvent(new Event('load'));
    message(newFrame, { protocol, type: 'ready' });
    expect(newPost.calls.mostRecent().args[0].generation).toBeGreaterThan(payload.generation);
    fixture.destroy();
    expect(newPost.calls.mostRecent().args[0].type).toBe('disconnect');
    requests.expectNone(request => request.method !== 'GET');
  });
});
