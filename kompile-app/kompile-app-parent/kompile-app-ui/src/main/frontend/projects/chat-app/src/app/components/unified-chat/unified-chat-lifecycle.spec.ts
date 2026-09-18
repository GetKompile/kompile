import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { UnifiedChatComponent } from './unified-chat.component';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { ChatStorageService } from '@shared/services/chat-storage.service';
import { AgentProvider } from '@shared/models/api-models';

describe('Browser conversation lifecycle', () => {
  let component: UnifiedChatComponent;
  let service: LocalAgentChatService;
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
    history = jasmine.createSpyObj('history', ['getSessionMessages']);
    confirmation = new Subject<boolean>();
    const unused = null as any;
    component = new UnifiedChatComponent(unused, service, unused, unused, history, unused, unused, unused,
      unused, unused, unused, unused, jasmine.createSpyObj('cdr', ['detach', 'reattach', 'detectChanges', 'markForCheck']),
      unused, unused, { open: () => ({ afterClosed: () => confirmation }) } as any,
      jasmine.createSpyObj('snack', ['open']), unused);
    spyOn<any>(component, 'updateMonitorSubscription');
    spyOn<any>(component, 'refreshContextBudget');
    component.selectedAgent = { name: 'coder', displayName: 'Coder' } as AgentProvider;
    component.agents = [component.selectedAgent];
    component.newChat();
  });
  afterEach(() => {
    (component as any).cleanupStreaming();
    (component as any).unsubscribeStreamingSubs();
    if (saved === null) localStorage.removeItem('unified_chat_sessions');
    else localStorage.setItem('unified_chat_sessions', saved);
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
