import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { By } from '@angular/platform-browser';
import { CommonModule } from '@angular/common';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { ConversationalRagService } from '@shared/services/conversational-rag.service';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { AgentService } from '@shared/services/agent.service';
import { ChatStorageService } from '@shared/services/chat-storage.service';
import { ChatHistoryService } from '@shared/services/chat-history.service';
import { CliTranscriptService } from '@shared/services/cli-transcript.service';
import { FolderService } from '@shared/services/folder.service';
import { ModelContextService } from '@shared/services/model-context.service';
import { WebSocketService } from '@shared/services/websocket.service';
import { SystemPromptService } from '@shared/services/system-prompt.service';
import { ToolCallChartComponent } from '@shared/components/tool-call-chart/tool-call-chart.component';
import { ToolUseEvent } from '@shared/models/api-models';
import { UnifiedChatComponent } from './unified-chat.component';
import { ProductInlineBoardCardComponent } from '../product-chat/product-inline-board-card.component';
import { ProductChatPageComponent } from '../product-chat/product-chat-page.component';
import { ProductChatContextService } from '../product-chat/product-chat-context.service';

const turnId = '0123456789abcdef0123456789abcdef';
const event = (): ToolUseEvent => ({ tool: 'mcp__elthoria__show_tactical_board', input: '', callId: 'call-1',
  status: 'completed', ok: true, textOffset: 7, detail: { displayName: 'Show Tactical Board',
    presentation: { version: 1, kind: 'elthoria.tactical-board',
      data: { campaignId: 3, savedGridId: 7, runtimeId: '12345678-1234-1234-1234-123456789abc', observedRevision: 'r-current-1' },
      source: { serverId: 'elthoria', toolName: 'show_tactical_board' },
      identity: { sessionId: 'inline-chat', turnId, callId: 'call-1' } } } });

const profile = { productId: 'elthoria', title: 'Play', workingDirectory: '/managed/play',
  chat: { id: 'inline-chat', name: 'Play' }, agentName: 'coder', status: 'IDLE',
  panelsUrl: '/api/agents/chat/product/assets/index.html', domainBaseUrl: '/api/agents/chat/product/domain' };

describe('UnifiedChat real Angular host-owned presentation slot', () => {
  let fixture: ComponentFixture<ProductChatPageComponent>;
  let requests: HttpTestingController;
  let chat: UnifiedChatComponent;
  let service: any;
  beforeEach(async () => {
    const unused = {};
    service = { backendUrl: '/api', getTranscriptReloads: () => new Subject<string>(),
      getReconnectBookmark: () => null, detachStreaming: () => {} };
    await TestBed.configureTestingModule({
      imports: [CommonModule, FormsModule, NoopAnimationsModule, HttpClientTestingModule,
        MatButtonModule, MatMenuModule, ToolCallChartComponent],
      declarations: [ProductChatPageComponent, UnifiedChatComponent, ProductInlineBoardCardComponent],
      providers: [ { provide: LocalAgentChatService, useValue: service },
        { provide: ProductChatContextService, useValue: { load: () => Promise.resolve(null) } },
        ...[ConversationalRagService, AgentService, ChatStorageService, ChatHistoryService,
          CliTranscriptService, FolderService, ModelContextService, WebSocketService,
          SystemPromptService, MatDialog, MatSnackBar, Router].map(provide => ({ provide, useValue: unused })),
        { provide: ActivatedRoute, useValue: null } ],
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).overrideComponent(ProductChatPageComponent, { set: { providers: [] } }).compileComponents();
    // Lifecycle I/O is outside this template test; all three production templates remain real.
    spyOn(ProductChatPageComponent.prototype, 'ngOnInit').and.stub();
    fixture = TestBed.createComponent(ProductChatPageComponent);
    fixture.componentInstance.profile = profile; fixture.componentInstance.sessionOpen = true;
    // A profile-driven side pane would sanitize its fixed asset URL during template init;
    // presentation slot tests focus on chat-pane templates, not that deployment-configured URL.
    fixture.componentInstance.panelsUrl = null;
    fixture.detectChanges();
    requests = TestBed.inject(HttpTestingController);
    requests.expectOne(request => request.url.endsWith('/product/transcript')).flush({ sessionId: 'inline-chat', turns: [] });
    fixture.detectChanges(); chat = fixture.debugElement.query(By.directive(UnifiedChatComponent)).componentInstance;
  });
  afterEach(() => { fixture.destroy(); requests.verify(); });
  const root = () => fixture.nativeElement as HTMLElement;
  function answer(calls: ToolUseEvent[], content = '**One** after'): void {
    chat.messages = [{ id: 'assistant-1', role: 'assistant', content, timestamp: new Date(), toolUses: calls }] as any;
    (chat as any).cdr.markForCheck(); fixture.detectChanges();
  }

  it('reports only strict busy/idle readiness after server discovery, never tool results', async () => {
    let settle!: (value: null) => void;
    service.discoverRun = () => new Promise(resolve => settle = resolve);
    const events: unknown[] = [];
    chat.hostEvent.subscribe(value => events.push(value));
    const pending = (chat as any).discoverCurrentRun();
    expect(fixture.componentInstance.inlineRunIdle).toBeFalse();
    settle(null); await pending;
    expect(events).toEqual([
      { type: 'readiness', status: 'busy', productId: 'elthoria', sessionId: 'inline-chat', agentName: 'coder' },
      { type: 'readiness', status: 'idle', productId: 'elthoria', sessionId: 'inline-chat', agentName: 'coder' }
    ]);
    expect(fixture.componentInstance.inlineRunIdle).toBeTrue();
  });

  it('instantiates the real product template adjacent to its tool card, never inside Markdown', () => {
    answer([event()]);
    const slot = root().querySelector('[data-testid="tool-presentation-slot"]')!;
    expect(slot).not.toBeNull();
    expect(slot.previousElementSibling?.getAttribute('data-testid')).toBe('tool-call-card');
    expect(slot.querySelector('app-product-inline-board-card [data-testid="inline-board-card"]')).not.toBeNull();
    expect(root().querySelector('.md-rendered app-product-inline-board-card')).toBeNull();
    expect(root().querySelector('.md-rendered strong')?.textContent).toBe('One');
    expect(root().querySelector('app-product-inline-board-card iframe')).toBeNull(); // real run idle is not yet confirmed
    expect(fixture.componentInstance.campaignId).toBeNull();
    expect(fixture.componentInstance.savedGridId).toBeNull(); // no manual ID inputs are needed for the card
    requests.expectNone(request => request.method !== 'GET');
  });

  it('leaves ordinary Markdown, charts and tool cards intact with no metadata or host registration', () => {
    const call = event(); delete call.detail!.presentation;
    call.detail!.chart = { v: 1, kind: 'bar', title: 'Counts', labels: ['a'], series: [{ name: 'n', values: [1] }] };
    answer([call], '**One** `code` <app-product-inline-board-card></app-product-inline-board-card>');
    expect(root().querySelectorAll('[data-testid="tool-call-card"]').length).toBe(1);
    expect(root().querySelector('app-tool-call-chart')).not.toBeNull();
    expect(root().querySelector('app-product-inline-board-card')).toBeNull();
    expect(root().querySelector('.md-rendered code')?.textContent).toBe('code');
    chat.toolPresentationRegistrations = []; answer([event()]);
    expect(root().querySelector('[data-testid="tool-presentation-slot"]')).toBeNull();
  });

  it('rejects errored, source/identity-mismatched and rogue-URL data without creating components', () => {
    for (const change of [
      (e: any) => e.ok = false, (e: any) => e.detail.error = 'denied', (e: any) => e.status = 'started',
      (e: any) => e.detail.presentation.identity.callId = 'other-call',
      (e: any) => e.detail.presentation.identity.sessionId = 'other-chat',
      (e: any) => e.detail.presentation.source.serverId = 'other',
      (e: any) => e.detail.presentation.data.savedGridId = Number.MAX_SAFE_INTEGER + 1,
      (e: any) => e.detail.presentation.data.url = 'https://rogue.invalid'
    ]) {
      const call = event(); change(call); answer([call]);
      expect(root().querySelector('app-product-inline-board-card')).withContext(change.toString()).toBeNull();
    }
  });

  it('dedupes live replay and restores canonical metadata through the same validation on reload', () => {
    const call = event(); answer([{ ...call, status: 'started' }, call, { ...call }]);
    expect(root().querySelectorAll('app-product-inline-board-card').length).toBe(1);
    chat.refreshWorkspaceTranscript();
    const saved = { role: 'ASSISTANT', content: '**One** after', productTurnId: turnId,
      toolPresentations: [{ callId: call.callId, toolName: call.tool, textOffset: 7, presentation: call.detail!.presentation },
        { callId: call.callId, toolName: call.tool, textOffset: 7, presentation: call.detail!.presentation }] };
    requests.expectOne(request => request.url.endsWith('/product/transcript')).flush({ sessionId: 'inline-chat', turns: [saved] });
    fixture.detectChanges();
    expect(root().querySelectorAll('app-product-inline-board-card').length).toBe(1);
    chat.refreshWorkspaceTranscript();
    requests.expectOne(request => request.url.endsWith('/product/transcript')).flush({ sessionId: 'inline-chat',
      turns: [{ ...saved, productTurnId: 'fedcba9876543210fedcba9876543210' }] });
    fixture.detectChanges();
    expect(root().querySelector('app-product-inline-board-card')).toBeNull();
  });
});
