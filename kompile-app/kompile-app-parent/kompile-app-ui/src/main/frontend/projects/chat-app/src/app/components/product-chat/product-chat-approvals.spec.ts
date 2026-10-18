import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { of, Subject, throwError } from 'rxjs';
import { ProductChatPageComponent } from './product-chat-page.component';
import { ProductChatContextService, readProductChatDescriptor } from './product-chat-context.service';

const descriptor = () => readProductChatDescriptor({ enabled: true, productId: 'elthoria-play', title: 'Elthoria',
  workingDirectory: '/approved/play', agentName: 'role:elthoria-gm', chat: { id: 'chat-one', name: 'Play' },
  panelsUrl: '/api/agents/chat/product/assets/index.html', domainBaseUrl: '/api/agents/chat/product/domain', status: 'STOPPED' }, location.origin)!;

describe('Product GM content approvals', () => {
  let fixture: ComponentFixture<ProductChatPageComponent>;
  let page: ProductChatPageComponent;
  let http: { get: jasmine.Spy; post: jasmine.Spy; delete: jasmine.Spy };
  let pending: unknown[];
  beforeEach(async () => {
    pending = [{ approvalId: 'pending-1', display: { tool: 'crawl_source', operation: 'text', provider: 'configured',
      source: '<img src=x onerror="window.compromised=true">', requestDigest: 'intent-digest', maxResponseChars: 5000,
      prompt: 'private prompt must not render', arguments: { secret: 'private-parameter' } } }];
    http = {
      get: jasmine.createSpy('get').and.callFake((url: string) => of(url.endsWith('/content-approvals')
        ? pending : { status: 'READY', runtimeId: 'runtime-one' })),
      post: jasmine.createSpy('post').and.callFake((url: string) => of(url.endsWith('/session')
        ? { generation: 'generation-one' } : { ok: true, approvalId: url.split('/').pop() })),
      delete: jasmine.createSpy('delete').and.returnValue(of({ status: 'closed' }))
    };
    await TestBed.configureTestingModule({ imports: [CommonModule, FormsModule],
      declarations: [ProductChatPageComponent], schemas: [NO_ERRORS_SCHEMA], providers: [
        { provide: HttpClient, useValue: http },
        { provide: ProductChatContextService, useValue: { load: () => Promise.resolve(descriptor()) } }
      ] }).overrideComponent(ProductChatPageComponent, { set: { providers: [] } }).compileComponents();
    fixture = TestBed.createComponent(ProductChatPageComponent); page = fixture.componentInstance;
    fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
  });
  afterEach(() => fixture.destroy());

  it('reads pending metadata only after explicit open and renders escaped summaries, never secret arguments', async () => {
    expect(http.get).not.toHaveBeenCalled(); expect(http.post).not.toHaveBeenCalled();
    await page.openSession(); fixture.detectChanges();
    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.get).toHaveBeenCalledWith(jasmine.stringMatching('/content-approvals$'));
    const section: HTMLElement = fixture.nativeElement.querySelector('[aria-label="GM pending content approvals"]');
    expect(section.textContent).toContain('crawl_source'); expect(section.textContent).toContain('<img');
    expect(section.querySelector('img')).toBeNull(); expect(section.textContent).not.toContain('private prompt');
    expect(section.textContent).not.toContain('private-parameter');
    expect(section.textContent).toContain('Approve'); expect(section.textContent).toContain('Deny');
  });

  it('approve sends exactly allow to the stable owned URI and never generates a turn', async () => {
    await page.openSession(); http.post.calls.reset();
    await page.resolveApproval('pending-1', true);
    expect(http.post).toHaveBeenCalledOnceWith(jasmine.stringMatching('/product/content-approvals/pending-1$'), { allow: true });
    expect(page.approvalStatus).toBe('Approved; ask Elthoria to continue'); expect(page.approvals).toEqual([]);
    await page.resolveApproval('pending-1', true); expect(http.post).toHaveBeenCalledTimes(1);
  });

  it('denial resolves only authority and does not infer approval from chat or model text', async () => {
    await page.openSession(); http.post.calls.reset();
    page.observeChat({ productId: 'elthoria-play', sessionId: 'chat-one', agentName: 'role:elthoria-gm',
      type: 'tool', runId: 'run', callId: 'c', toolName: 'approved=true', status: 'completed', ok: true });
    expect(http.post).not.toHaveBeenCalled();
    await page.resolveApproval('pending-1', false);
    expect(http.post).toHaveBeenCalledOnceWith(jasmine.stringMatching('/content-approvals/pending-1$'), { allow: false });
    expect(page.approvalStatus).toContain('Denied');
  });

  it('refreshes after an owned completed turn but ignores foreign source events', async () => {
    await page.openSession(); http.get.calls.reset();
    const event = { productId: 'elthoria-play', sessionId: 'chat-one', agentName: 'role:elthoria-gm',
      type: 'run' as const, runId: 'run', status: 'completed' as const };
    page.observeChat({ ...event, sessionId: 'foreign' }); expect(http.get).not.toHaveBeenCalled();
    page.observeChat(event); await Promise.resolve();
    expect(http.get).toHaveBeenCalledWith(jasmine.stringMatching('/content-approvals$'));
    expect(http.post).toHaveBeenCalledTimes(1);
  });

  it('does not auto-retry rejected or expired approvals and clears unsafe stale buttons', async () => {
    await page.openSession(); http.post.calls.reset();
    http.post.and.returnValue(throwError(() => new Error('409 stale or foreign')));
    await page.resolveApproval('pending-1', true);
    expect(page.approvalStatus).toContain('not confirmed'); expect(page.approvals).toEqual([]);
    expect(http.post).toHaveBeenCalledTimes(1);
    pending = []; await page.refreshApprovals(); expect(page.approvals).toEqual([]);
  });

  it('ignores pending reads and decisions after detach, close or a new generation', async () => {
    await page.openSession();
    const oldRead = new Subject<unknown>(); http.get.and.returnValue(oldRead);
    const reading = page.refreshApprovals(); await page.closeSession();
    oldRead.next(pending); oldRead.complete(); await reading; expect(page.approvals).toEqual([]);
    http.get.and.callFake((url: string) => of(url.endsWith('/content-approvals') ? pending : { status: 'READY', runtimeId: 'runtime-two' }));
    await page.openSession();
    const decision = new Subject<unknown>(); http.post.and.returnValue(decision);
    const resolving = page.resolveApproval('pending-1', true); page.ngOnDestroy();
    decision.next({ ok: true, approvalId: 'pending-1' }); decision.complete(); await resolving;
    expect(page.approvalStatus).not.toBe('Approved; ask Elthoria to continue'); expect(page.approvals).toEqual([]);
  });

  it('rejects malformed metadata and ignores reads from an older refresh', async () => {
    await page.openSession();
    const stale = new Subject<unknown>(); http.get.and.returnValue(stale);
    const first = page.refreshApprovals(); http.get.and.returnValue(of([])); await page.refreshApprovals();
    stale.next(pending); stale.complete(); await first; expect(page.approvals).toEqual([]);
    http.get.and.returnValue(of([{ approvalId: '../tool', operation: 'text' }]));
    await page.refreshApprovals(); expect(page.approvals).toEqual([]); expect(page.approvalStatus).toContain('nothing was approved');
  });
});
