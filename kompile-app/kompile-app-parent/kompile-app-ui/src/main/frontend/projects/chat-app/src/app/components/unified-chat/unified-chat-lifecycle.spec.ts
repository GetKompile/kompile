import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { HttpClient } from '@angular/common/http';
import { Subject } from 'rxjs';
import { UnifiedChatComponent } from './unified-chat.component';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { ChatStorageService } from '@shared/services/chat-storage.service';
import { AgentProvider } from '@shared/models/api-models';

describe('Browser conversation lifecycle', () => {
  let component: UnifiedChatComponent;
  let service: LocalAgentChatService;
  let http: HttpTestingController;
  const workspaceId = 'web-test-workspace-identity';
  const workspaceKey = 'unified_chat_workspace:' + workspaceId;
  let history: jasmine.SpyObj<any>;
  let confirmation: Subject<boolean>;
  let saved: string | null;
  const transcript = (id: string) => ({ id, name: id, messages: [], synced: true,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() });
  beforeEach(() => {
    saved = localStorage.getItem('unified_chat_sessions');
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule], providers: [LocalAgentChatService,
      { provide: ChatStorageService, useValue: jasmine.createSpyObj('storage', ['updateSession', 'updateTab']) }] });
    service = TestBed.inject(LocalAgentChatService);
    http = TestBed.inject(HttpTestingController);
    history = jasmine.createSpyObj('history', ['getSessionMessages']);
    confirmation = new Subject<boolean>();
    const unused = null as any;
    component = new UnifiedChatComponent(unused, service, unused, unused, history, unused, unused, unused,
      unused, unused, unused, TestBed.inject(HttpClient), jasmine.createSpyObj('cdr', ['detach', 'reattach', 'detectChanges', 'markForCheck']),
      unused, unused, { open: () => ({ afterClosed: () => confirmation }) } as any,
      jasmine.createSpyObj('snack', ['open']), unused, unused);
    spyOn<any>(component, 'updateMonitorSubscription');
    spyOn<any>(component, 'refreshContextBudget');
    component.selectedAgent = { name: 'coder', displayName: 'Coder' } as AgentProvider;
    component.agents = [component.selectedAgent];
    component.newChat();
  });
  afterEach(() => {
    (component as any).cleanupStreaming();
    (component as any).unsubscribeStreamingSubs();
    http.verify();
    localStorage.removeItem(workspaceKey);
    localStorage.removeItem(workspaceKey + ':active');
    if (saved === null) localStorage.removeItem('unified_chat_sessions');
    else localStorage.setItem('unified_chat_sessions', saved);
  });
  const openWorkspace = () => {
    component.workspaceChat = { id: workspaceId, name: 'CLI chat' };
    component.workingDirectory = '/test/project with spaces';
    (component as any).loadSessions();
    return http.expectOne(request => request.url.endsWith('/agents/chat/workspace/transcript'));
  };
  it('derives the active title indicator from live state and clears it after transport cleanup', () => {
    component.isStreaming = true;
    component.messages = [{ id: 'live-answer', role: 'assistant', content: 'Answer', timestamp: new Date(), isStreaming: true }];
    expect(component.activityIndicator.label).toBe('Responding');
    component.isCompacting = true;
    expect(component.activityIndicator.label).toBe('Compacting');
    component.isCompacting = false;
    service.harnessActivity = { backgroundable: false, turnActive: false,
      processes: [{ id: 'p', description: 'Build', state: 'RUNNING', kind: 'command' }], tasks: [] };
    expect(component.activityIndicator.label).toBe('Running 1 process');
    (service as any).closeHarnessControls();
    component.isStreaming = false;
    expect(component.activityIndicator.active).toBeFalse();
  });

  it('creates UUID conversation identities while retaining existing local IDs', () => {
    expect(component.currentSession!.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    const legacy = { ...transcript('legacy-browser-id'), synced: false };
    component.loadSession(legacy);
    expect(component.currentSession!.id).toBe('legacy-browser-id');
  });
  it('hydrates writable canonical CLI turns instead of stale cache or a local active ID', () => {
    localStorage.setItem(workspaceKey, JSON.stringify([
      { ...transcript('wrong-active'), synced: false },
      { ...transcript(workspaceId), name: 'Browser label', agentName: 'coder', conversationId: 'rag-id',
        messages: [{ role: 'user', content: 'stale browser turn' }] }
    ]));
    localStorage.setItem(workspaceKey + ':active', 'wrong-active');
    const request = openWorkspace();
    expect(request.request.params.get('sessionId')).toBe(workspaceId);
    expect(request.request.params.get('workingDirectory')).toBe('/test/project with spaces');
    expect(component.lifecycleBusy).toBeTrue();
    const send = spyOn(service, 'sendMessage').and.resolveTo();
    component.userInput = 'waiting';
    component.sendMessage();
    expect(send).not.toHaveBeenCalled();
    expect(component.messages).toEqual([]);
    request.flush({ sessionId: workspaceId, turns: [
      { role: 'USER', content: 'CLI question' }, { role: 'assistant', content: 'CLI answer' }
    ] });
    expect(component.currentSession!.id).toBe(workspaceId);
    expect(component.sessions.some(session => session.id === 'wrong-active')).toBeTrue();
    expect(component.currentSession!.name).toBe('Browser label');
    expect(component.currentSession!.agentName).toBe('coder');
    expect(component.currentConversationId).toBe('rag-id');
    expect(component.transcriptReadOnly).toBeFalse();
    expect(component.lifecycleBusy).toBeFalse();
    expect(component.messages.map(message => message.content)).toEqual(['CLI question', 'CLI answer']);
    expect(component.messages.map(message => message.role)).toEqual(['user', 'assistant']);
    component.sendMessage();
    expect(send.calls.mostRecent().args[3]).toEqual(jasmine.objectContaining({
      sessionId: workspaceId, workingDirectory: '/test/project with spaces', includeHistory: false
    }));
    expect(history.getSessionMessages).not.toHaveBeenCalled();
  });
  it('always refreshes persisted turns on open and accepts an empty new transcript', () => {
    openWorkspace().flush({ sessionId: workspaceId, turns: [{ role: 'user', content: 'before' }] });
    const draft = { name: 'draft.txt', textContent: 'draft' } as any;
    component.pendingAttachments = [draft];
    component.refreshWorkspaceTranscript();
    expect(component.pendingAttachments).toEqual([draft]);
    http.expectOne(request => request.url.endsWith('/workspace/transcript')).flush({
      sessionId: workspaceId, turns: [{ role: 'assistant', content: 'external CLI update' }]
    });
    expect(component.messages.map(message => message.content)).toEqual(['external CLI update']);
    component.refreshWorkspaceTranscript();
    http.expectOne(request => request.url.endsWith('/workspace/transcript')).flush({ sessionId: workspaceId, turns: [] });
    expect(component.messages).toEqual([]);
    expect(component.currentSession!.id).toBe(workspaceId);
    expect(component.transcriptReadOnly).toBeFalse();
  });
  it('blocks sends after hydration failure rather than falling back to cached history, and retries', () => {
    openWorkspace().flush({ message: 'unavailable' }, { status: 503, statusText: 'Unavailable' });
    const send = spyOn(service, 'sendMessage').and.resolveTo();
    component.userInput = 'next';
    component.sendMessage();
    expect(send).not.toHaveBeenCalled();
    expect(component.messages).toEqual([]);
    component.refreshWorkspaceTranscript();
    http.expectOne(request => request.url.endsWith('/workspace/transcript')).flush({ sessionId: workspaceId, turns: [] });
    component.sendMessage();
    expect(send).toHaveBeenCalled();
  });
  it('does not replace an active harness transport when its workspace pane is reopened', () => {
    openWorkspace().flush({ sessionId: workspaceId, turns: [] });
    component.isStreaming = true;
    const session = component.currentSession;
    component.refreshWorkspaceTranscript();
    expect(component.currentSession).toBe(session);
    http.expectNone(request => request.url.endsWith('/workspace/transcript'));
  });
  it('rejects a different returned identity without enabling a write', () => {
    openWorkspace().flush({ sessionId: 'other-id', turns: [] });
    const send = spyOn(service, 'sendMessage');
    component.userInput = 'next';
    component.sendMessage();
    expect(send).not.toHaveBeenCalled();
    expect(component.currentSession!.id).toBe(workspaceId);
  });
  it('does not apply transcript hydration after pane destruction', () => {
    const pending = openWorkspace();
    (component as any).harnessViewDestroyed = true;
    (component as any).destroy$.next();
    expect(pending.cancelled).toBeTrue();
    expect(component.messages).toEqual([]);
  });
  it('sends workspace turns through the shared harness without a duplicate wire history', async () => {
    openWorkspace().flush({ sessionId: workspaceId, turns: [{ role: 'user', content: 'persisted' }] });
    const fetch = spyOn(window, 'fetch').and.resolveTo(new Response(new ReadableStream({ start(controller) {
      controller.enqueue(new TextEncoder().encode('event: complete\ndata: {"content":"done"}\n\n'));
      controller.close();
    } })));
    component.userInput = 'next';
    component.sendMessage();
    await new Promise(resolve => setTimeout(resolve, 0));
    const request = JSON.parse(String(fetch.calls.mostRecent().args[1]!.body));
    expect(request.sessionId).toBe(workspaceId);
    expect(request.workingDirectory).toBe('/test/project with spaces');
    expect(request.message).toBe('next');
    expect(request.includeHistory).toBeFalse();
    expect(request.chatHistory).toBeUndefined();
  });
  it('delegates workspace New and Start fresh to the manager without orphaning the canonical identity', () => {
    openWorkspace().flush({ sessionId: workspaceId, turns: [{ role: 'user', content: 'preserved' }] });
    const session = component.currentSession;
    const request = jasmine.createSpy('workspaceNewChat');
    component.workspaceNewChat.subscribe(request);
    component.newChat();
    component.clearConversation(); confirmation.next(true);
    expect(request).toHaveBeenCalledTimes(2);
    expect(component.currentSession).toBe(session);
    expect(component.messages[0].content).toBe('preserved');
    component.forkFromMessage(0);
    expect(component.currentSession).toBe(session);
    component.isStreaming = true;
    component.newChat();
    expect(request).toHaveBeenCalledTimes(2);
  });
  it('preserves the previous conversation when starting fresh', () => {
    const old = component.currentSession!;
    component.clearConversation();
    confirmation.next(true);
    expect(component.currentSession!.id).not.toBe(old.id);
    expect(component.sessions).toContain(old);
  });
  it('rejects a late confirmation after another selection', () => {
    component.clearConversation();
    component.newChat();
    const selected = component.currentSession;
    confirmation.next(true);
    expect(component.currentSession).toBe(selected);
  });
  it('rejects a delete confirmation after a turn starts and finishes', () => {
    const old = component.currentSession!;
    component.deleteSession(old);
    spyOn(service, 'sendMessage').and.resolveTo();
    component.userInput = 'new turn';
    component.sendMessage();
    component.isStreaming = false; // a completed turn still invalidates the old confirmation
    confirmation.next(true);
    expect(component.sessions).toContain(old);
  });
  it('ignores out-of-order transcript responses and responses after New', () => {
    const first = new Subject<any[]>();
    const second = new Subject<any[]>();
    history.getSessionMessages.and.returnValues(first, second);
    component.loadSyncedSession(transcript('first'));
    component.loadSyncedSession(transcript('second'));
    second.next([{ role: 'USER', content: 'second' }]);
    first.next([{ role: 'USER', content: 'first' }]);
    expect(component.currentSession!.id).toBe('second');
    component.newChat();
    second.next([{ role: 'USER', content: 'late' }]);
    expect(component.messages).toEqual([]);
  });
  it('ignores a pending transcript response after a turn has completed', () => {
    const pending = new Subject<any[]>();
    history.getSessionMessages.and.returnValue(pending);
    const old = component.currentSession;
    component.loadSyncedSession(transcript('remote'));
    spyOn(service, 'sendMessage').and.resolveTo();
    component.userInput = 'new turn';
    component.sendMessage();
    component.isStreaming = false;
    pending.next([{ role: 'USER', content: 'stale' }]);
    expect(component.currentSession).toBe(old);
  });
  it('does not launch a canceled send after asynchronous compaction', async () => {
    let finish!: () => void;
    spyOn<any>(component, 'needsCompactionBeforeSend').and.returnValue(true);
    spyOn(component, 'compactContext').and.returnValue(new Promise<void>(resolve => finish = resolve));
    const send = spyOn(service, 'sendMessage').and.resolveTo();
    component.userInput = 'turn';
    component.sendMessage();
    component.cancelStreaming();
    component.newChat();
    const selected = component.currentSession;
    finish();
    await Promise.resolve();
    expect(send).not.toHaveBeenCalled();
    expect(component.currentSession).toBe(selected);
    expect(component.messages).toEqual([]);
  });
  it('blocks lifecycle changes during streaming or compaction', () => {
    const old = component.currentSession;
    for (const busy of ['isStreaming', 'isCompacting']) {
      (component as any)[busy] = true;
      component.newChat();
      component.loadSyncedSession(transcript('remote'));
      component.loadSession({ ...transcript('local'), synced: false });
      component.deleteSession(old!);
      expect(component.currentSession).toBe(old);
      expect(history.getSessionMessages).not.toHaveBeenCalled();
      (component as any)[busy] = false;
    }
  });
  it('copies only text into a fresh identity and never sends from a read-only transcript', () => {
    component.currentSession = transcript('remote');
    component.messages = [{ id: 'db-msg', dbId: 42, role: 'user', content: 'context', timestamp: new Date() }];
    component.userInput = 'reply';
    const send = spyOn(service, 'sendMessage');
    component.sendMessage();
    expect(send).not.toHaveBeenCalled();
    component.continueAsNewConversation();
    expect(component.currentSession!.id).not.toBe('remote');
    expect(component.transcriptReadOnly).toBeFalse();
    expect(component.messages[0].content).toBe('context');
    expect(component.messages[0].dbId).toBeUndefined();
  });
  it('resumes browser identity with prior text in real service wire history exactly once', async () => {
    const old = component.currentSession!;
    old.messages = [
      { id: 'u', role: 'user', content: 'before', timestamp: new Date() },
      { id: 'a', role: 'assistant', content: 'answer', timestamp: new Date() },
      { id: 'cmd', role: 'assistant', content: 'command output', timestamp: new Date(), commandOnly: true }
    ];
    component.newChat();
    component.loadSession(old);
    const fetch = spyOn(window, 'fetch').and.resolveTo(new Response(new ReadableStream({ start(c) {
      c.enqueue(new TextEncoder().encode('event: complete\ndata: {"content":"done"}\n\n'));
      c.close();
    } })));
    component.userInput = 'next';
    component.sendMessage();
    await new Promise(resolve => setTimeout(resolve, 0));
    const request = JSON.parse(String(fetch.calls.mostRecent().args[1]!.body));
    expect(request.sessionId).toBe(old.id);
    expect(request.message).toBe('next');
    expect(request.chatHistory).toEqual([{ role: 'USER', content: 'before' }, { role: 'ASSISTANT', content: 'answer' }]);
  });
});
