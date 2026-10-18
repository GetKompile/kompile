import { of, Subject } from 'rxjs';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
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
import { MarkdownRendererService } from '@shared/services/markdown-renderer.service';
import { UnifiedChatComponent } from './unified-chat.component';
// Compile against the component's type-only public embedding exports (strict isolatedModules).
import type { ChatHostContext, ChatHostEvent } from './unified-chat.component';

const context = (): ChatHostContext => ({ productId: 'test-product', title: 'Product assistant',
  workingDirectory: '/managed/product', chat: { id: 'product-chat-1', name: 'Product chat' }, agentName: 'coder' });

describe('UnifiedChat managed product embedding', () => {
  let component: UnifiedChatComponent;
  let transcript: Subject<any>;
  let content: Subject<string>;
  let complete: Subject<any>;
  let errors: Subject<string>;
  let tools: Subject<any[]>;
  let live: Subject<any[]>;
  let reloads: Subject<string>;
  let bookmark: any;
  let service: any;
  let http: any;
  let events: ChatHostEvent[];

  beforeEach(() => {
    transcript = new Subject(); content = new Subject(); complete = new Subject(); errors = new Subject();
    tools = new Subject(); live = new Subject(); reloads = new Subject(); bookmark = null;
    service = {
      backendUrl: '/api', getTranscriptReloads: () => reloads,
      getReconnectBookmark: jasmine.createSpy('bookmark').and.callFake(() => bookmark),
      getStreamingContent: () => content, getStreamingComplete: () => complete,
      getStreamingError: () => errors, getChatStats: () => new Subject(), getToolCalls: () => tools,
      getLiveMessages: () => live, getSessionTitle: () => new Subject(),
      createSession: jasmine.createSpy('createSession').and.returnValue({ id: 'wire-seed', messages: [] }),
      sendMessage: jasmine.createSpy('sendMessage').and.callFake(() => {
        bookmark = { runId: 'harness-one', browserSessionId: context().chat.id, agent: { name: 'coder' } };
        return Promise.resolve();
      }),
      detachStreaming: jasmine.createSpy('detachStreaming'),
      cancelStreaming: jasmine.createSpy('cancelStreaming'),
      sendHarnessControl: jasmine.createSpy('sendHarnessControl'),
      getCompaction: jasmine.createSpy('getCompaction').and.returnValue(new Subject())
    };
    http = { get: jasmine.createSpy('get').and.returnValue(transcript) };
    const unused = null as any;
    const cdr = jasmine.createSpyObj('cdr', ['markForCheck', 'detectChanges', 'detach', 'reattach']);
    component = new UnifiedChatComponent(unused, service, unused, unused, unused, unused, unused,
      unused, unused, unused, unused, http, cdr, unused, unused, unused,
      { open: jasmine.createSpy('snack') } as any, unused, unused);
    events = [];
    component.hostEvent.subscribe(event => events.push(event));
  });

  afterEach(() => component.ngOnDestroy());

  function mount(): void {
    component.hostContext = context();
    component.ngOnInit();
  }
  function ready(): void {
    mount();
    transcript.next({ sessionId: context().chat.id, turns: [] });
  }
  function send(): void {
    component.userInput = 'Help with this product';
    component.sendMessage();
  }

  it('leaves the ordinary initialization path intact when no hostContext is supplied', () => {
    const initializers = ['registerSessionConfigOpener', 'loadSessions', 'checkRagServiceStatus',
      'loadAgents', 'loadFolders', 'initModelContext', 'loadSystemPrompts'];
    const spies = initializers.map(name => spyOn<any>(component, name));
    (component as any).agentService = { agents$: of([]) };
    (component as any).folderService = { folders$: of([]), selectedFolder$: of(null) };
    component.ngOnInit();
    spies.forEach(spy => expect(spy).toHaveBeenCalledTimes(1));
    expect(service.getCompaction).toHaveBeenCalledTimes(1);
    expect(component.managedEmbedding).toBeFalse();
  });

  it('initializes only the pinned transcript, not generic discovery, prompts, model context, storage or monitor', () => {
    const forbidden = ['registerSessionConfigOpener', 'checkRagServiceStatus', 'loadAgents', 'loadFolders',
      'initModelContext', 'loadSystemPrompts', 'loadSyncedSessions'];
    const spies = forbidden.map(name => spyOn<any>(component, name));
    const storage = spyOn(Storage.prototype, 'getItem').and.callThrough();
    // Null service doubles intentionally fail if managed init touches generic subscriptions/read services.
    mount();
    spies.forEach(spy => expect(spy).not.toHaveBeenCalled());
    expect(storage).not.toHaveBeenCalled();
    expect(service.getCompaction).not.toHaveBeenCalled();
    expect(http.get).toHaveBeenCalledOnceWith('/api/agents/chat/product/transcript',
      { params: { workingDirectory: '/managed/product', sessionId: 'product-chat-1' } });
    expect(component.currentSession?.id).toBe('product-chat-1');
    expect(component.currentSession?.agentName).toBe('coder');
    expect(component.sessions.map(session => session.id)).toEqual(['product-chat-1']);
    expect(component.showHistorySidebar).toBeFalse();
  });

  it('copies and freezes nested host identity and requires remounting to change it', () => {
    const supplied = context();
    component.hostContext = supplied;
    (supplied as any).agentName = 'other';
    (supplied.chat as any).id = 'unrelated';
    component.ngOnInit();
    expect(component.hostContext?.agentName).toBe('coder');
    expect(component.currentSession?.id).toBe('product-chat-1');
    expect(Object.isFrozen(component.hostContext)).toBeTrue();
    expect(Object.isFrozen(component.hostContext?.chat)).toBeTrue();
    expect(() => component.hostContext = { ...context(), agentName: 'other' }).toThrowError(/remount/);
    expect(() => component.hostContext = undefined).toThrowError(/remount/);
    expect(() => component.hostContext = context()).not.toThrow();
  });

  it('rejects incomplete managed identity rather than falling through to ordinary mode', () => {
    expect(() => component.hostContext = { ...context(), workingDirectory: '' }).toThrowError(/ChatHostContext/);
  });

  it('pins session, role and directory on sends and refreshes without generic history or prompt overrides', async () => {
    ready();
    component.workingDirectory = '/unrelated';
    component.workspaceChat = { id: 'unrelated', name: 'Unrelated' };
    component.systemPrompt = 'Not a managed override';
    component.skipPermissions = true;
    component.ragEnabled = true;
    component.graphRagEnabled = true;
    component.refreshAgents();
    component.selectAgent({ name: 'other' } as any);
    send();
    expect(service.sendMessage).toHaveBeenCalledTimes(1);
    const [, , agent, options] = service.sendMessage.calls.mostRecent().args;
    expect(agent.name).toBe('coder');
    expect(options).toEqual({ managedProduct: true, sessionId: 'product-chat-1',
      workingDirectory: '/managed/product', includeHistory: false, messageStartIndex: 0, attachments: undefined });
    expect(options.systemPromptOverride).toBeUndefined();
    expect(options.folderId).toBeUndefined();
    complete.next({ content: 'Done', role: 'ASSISTANT' });
    await Promise.resolve();
    component.refreshWorkspaceTranscript();
    expect(http.get.calls.mostRecent().args[1].params).toEqual({
      workingDirectory: '/managed/product', sessionId: 'product-chat-1' });
    expect(component.currentSession?.agentName).toBe('coder');
    expect(component.activeChatTitle()).toBe('Product chat');
  });

  for (const liveControlsReady of [true, false]) {
    it('keeps managed follow-ups in the composer without control input or local queue (controls ' + liveControlsReady + ')', () => {
      ready(); send();
      service.liveControlsReady = liveControlsReady;
      const snapshot = JSON.stringify(component.messages);
      component.userInput = 'Follow up after this response';
      component.sendMessage();
      expect(component.harnessControlMessage).toMatch(/Wait.*finish or stop/);
      expect(component.userInput).toBe('Follow up after this response');
      expect(component.queuedMessages).toEqual([]);
      expect(service.sendHarnessControl).not.toHaveBeenCalled();
      expect(service.sendMessage).toHaveBeenCalledTimes(1);
      expect(JSON.stringify(component.messages)).toBe(snapshot);
      complete.next({ role: 'ASSISTANT', content: 'Done' });
      expect(service.sendMessage).toHaveBeenCalledTimes(1);
      expect(component.userInput).toBe('Follow up after this response');
    });
  }

  it('rejects direct managed input controls without clearing the composer or queuing a follow-up', async () => {
    ready(); send();
    service.liveControlsReady = true;
    component.userInput = 'Follow up';
    await component.sendHarnessControl('input', undefined, component.userInput);
    expect(service.sendHarnessControl).not.toHaveBeenCalled();
    expect(component.harnessControlMessage).toMatch(/Wait.*finish or stop/);
    expect(component.userInput).toBe('Follow up');
    expect(component.queuedMessages).toEqual([]);
  });

  it('does not automatically flush any parked product follow-ups on normal completion', () => {
    ready(); send();
    component.queuedMessages = ['Do not launch'];
    complete.next({ role: 'ASSISTANT', content: 'Done' });
    expect(service.sendMessage).toHaveBeenCalledTimes(1);
    expect(service.sendHarnessControl).not.toHaveBeenCalled();
  });

  it('stops only the current managed run through the existing cancel API without closing the product session', () => {
    ready(); send();
    component.cancelStreaming();
    expect(service.cancelStreaming).toHaveBeenCalledTimes(1);
    expect(component.isStreaming).toBeFalse();
    expect(component.currentSession?.id).toBe('product-chat-1');
    expect(component.hostContext?.chat.id).toBe('product-chat-1');
    expect(service.sendMessage).toHaveBeenCalledTimes(1);
  });

  it('does not browse, switch or write unrelated sessions or expose generic commands/config', async () => {
    ready();
    const writes = spyOn(Storage.prototype, 'setItem').and.callThrough();
    component.loadSyncedSessions();
    component.loadSyncedSession({ id: 'other' } as any);
    component.loadSession({ id: 'other' } as any);
    component.newChat();
    component.openCommandConfig(); // Null dialog intentionally catches accidental exposure.
    component.onFolderSelected({ folderId: 'other' } as any);
    component.clearFolderSelection();
    component.userInput = '/role other';
    component.slashMenuOpen = true;
    expect(component.slashSuggestions).toEqual([]);
    component.sendMessage();
    await component.sendHarnessControl('command', undefined, '/role other');
    expect(component.currentSession?.id).toBe('product-chat-1');
    expect(service.sendMessage).not.toHaveBeenCalled();
    expect(service.sendHarnessControl).not.toHaveBeenCalled();
    expect(writes).not.toHaveBeenCalled();
  });

  it('refuses a replay bookmark for another session or role', () => {
    ready();
    bookmark = { runId: 'harness-other', browserSessionId: 'other', agent: { name: 'coder' } };
    expect(component.reconnectBookmark).toBeNull();
    bookmark.browserSessionId = 'product-chat-1';
    bookmark.agent.name = 'other';
    expect(component.reconnectBookmark).toBeNull();
  });

  it('emits only identified completed tool cards and dedupes replay and terminal snapshots', () => {
    ready(); send();
    const success = { tool: 'product_save', input: 'not emitted', callId: 'call-one', status: 'completed', ok: true };
    const failure = { ...success, callId: 'call-two', ok: false };
    tools.next([{ ...success, status: 'started' }]);
    tools.next([success, failure, { ...success, callId: undefined }, { ...success, callId: 'unknown', ok: undefined }]);
    tools.next([success, failure]);
    live.next([{ id: 'replayed-answer', role: 'ASSISTANT', content: 'replayed', timestamp: new Date().toISOString(),
      streaming: true, toolUses: [success, failure] }]);
    complete.next({ role: 'ASSISTANT', content: 'prose is not host state', toolUses: [success, failure] });
    expect(events.filter(event => event.type === 'tool')).toEqual([
      { type: 'tool', productId: 'test-product', sessionId: 'product-chat-1', agentName: 'coder',
        runId: 'harness-one', callId: 'call-one', toolName: 'product_save', status: 'completed', ok: true },
      { type: 'tool', productId: 'test-product', sessionId: 'product-chat-1', agentName: 'coder',
        runId: 'harness-one', callId: 'call-two', toolName: 'product_save', status: 'completed', ok: false }
    ]);
    expect(events.filter(event => event.type === 'run').map(event => event.status)).toEqual(['attached', 'completed']);
    expect(JSON.stringify(events)).not.toContain('prose is not host state');
    expect(JSON.stringify(events)).not.toContain('not emitted');
  });

  it('does not equate stream errors with failed or cancelled server work', () => {
    ready(); send();
    errors.next('Network unavailable');
    expect(events.some(event => event.type === 'stream' && event.status === 'error')).toBeTrue();
    expect(events.some(event => event.type === 'run' && event.status !== 'attached')).toBeFalse();
  });

  it('ignores stale transcript responses after refresh or destroy', () => {
    mount();
    (component as any).workspaceTranscriptLoading = false;
    const newer = new Subject<any>();
    http.get.and.returnValue(newer);
    component.refreshWorkspaceTranscript();
    transcript.next({ sessionId: 'product-chat-1', turns: [{ role: 'assistant', content: 'stale' }] });
    expect(component.messages).toEqual([]);
    component.ngOnDestroy();
    newer.next({ sessionId: 'product-chat-1', turns: [{ role: 'assistant', content: 'late' }] });
    expect(component.messages).toEqual([]);
    expect(events).toEqual([]);
  });

  it('reports canonical stopped completion without flushing queued product messages', () => {
    ready(); send();
    component.queuedMessages = ['Do not launch'];
    complete.next({ role: 'ASSISTANT', content: 'Stopped', stopped: true });
    expect(events.some(event => event.type === 'run' && event.status === 'cancelled')).toBeTrue();
    expect(service.sendMessage).toHaveBeenCalledTimes(1);
  });

  it('ignores stream callbacks after detaching, without cancelling server work', () => {
    ready(); send();
    const snapshot = JSON.stringify(component.messages);
    const eventCount = events.length;
    component.detachRunView();
    content.next('stale'); live.next([{ role: 'ASSISTANT', content: 'wrong chat' }]);
    tools.next([{ tool: 'save', callId: 'late', status: 'completed', ok: true }]);
    complete.next({ role: 'ASSISTANT', content: 'stale completion' });
    expect(JSON.stringify(component.messages)).toBe(snapshot);
    expect(events.length).toBe(eventCount);
    expect(service.detachStreaming).toHaveBeenCalled();
  });
});

describe('UnifiedChat managed product template', () => {
  it('does not instantiate generic folder, model/config or insights surfaces', async () => {
    // The real template must gate these children, not just hide their already initialized DOM.
    const unused = {};
    const chat = { backendUrl: '/api', getTranscriptReloads: () => new Subject<string>(),
      getReconnectBookmark: () => null, detachStreaming: () => {} };
    await TestBed.configureTestingModule({
      imports: [CommonModule, FormsModule, NoopAnimationsModule, HttpClientTestingModule,
        MatButtonModule, MatMenuModule],
      declarations: [UnifiedChatComponent],
      providers: [
        { provide: LocalAgentChatService, useValue: chat },
        ...[ConversationalRagService, AgentService, ChatStorageService, ChatHistoryService,
          CliTranscriptService, FolderService, ModelContextService, WebSocketService,
          SystemPromptService, MarkdownRendererService, MatDialog, MatSnackBar, Router]
          .map(provide => ({ provide, useValue: unused })),
        { provide: ActivatedRoute, useValue: null }
      ],
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();
    const fixture = TestBed.createComponent(UnifiedChatComponent);
    fixture.componentInstance.hostContext = context();
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('[data-testid="managed-host-heading"]')?.textContent).toContain('Product assistant');
    for (const selector of ['.history-sidebar', '.settings-sidebar', '.folder-sidebar-container',
      'app-folder-sidebar', 'app-chat-model-selector', 'app-session-insights-drawer',
      '[data-testid="agent-select"]', '[data-testid="welcome-commands"]', '.graph-rag-toggle']) {
      expect(root.querySelector(selector)).withContext(selector).toBeNull();
    }
    expect(root.querySelector('[data-testid="chat-input"]')).not.toBeNull();
    const requests = TestBed.inject(HttpTestingController);
    requests.expectOne(request => request.url === '/api/agents/chat/product/transcript'
      && request.params.get('sessionId') === 'product-chat-1').flush({ sessionId: 'product-chat-1', turns: [] });
    requests.verify(); // There must be no generic agents/folders/config/insights/monitor reads.
    fixture.destroy();
  });
});
