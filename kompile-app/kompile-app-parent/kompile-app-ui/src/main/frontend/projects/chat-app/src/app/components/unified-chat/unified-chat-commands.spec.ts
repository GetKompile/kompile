import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { of, Subject } from 'rxjs';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { ChatStorageService } from '@shared/services/chat-storage.service';
import { ConversationalRagService } from '@shared/services/conversational-rag.service';
import { AgentService } from '@shared/services/agent.service';
import { ChatHistoryService } from '@shared/services/chat-history.service';
import { CliTranscriptService } from '@shared/services/cli-transcript.service';
import { FolderService } from '@shared/services/folder.service';
import { ModelContextService } from '@shared/services/model-context.service';
import { WebSocketService } from '@shared/services/websocket.service';
import { AgentProvider, CommandEventData, CommandOutcome, LocalAgentSession } from '@shared/models/api-models';
import { UnifiedChatComponent } from './unified-chat.component';

/** Fixture-mounted TestBed harness (mirrors unified-chat-rag.spec.ts) for DOM assertions. */
function createMenuTestBed() {
  const agentChatServiceSpy = jasmine.createSpyObj('LocalAgentChatService', [
    'getStreamingContent', 'getStreamingComplete', 'getStreamingError',
    'getChatStats', 'getSources', 'getFilesModified', 'sendMessage',
    'cancelStreaming', 'createSession', 'getToolUse', 'getCompaction'
  ]);
  const agentServiceSpy = jasmine.createSpyObj('AgentService', [
    'getAllAgents', 'getAvailableAgents', 'getChatHarnessAgents',
    'refreshChatHarnessAgents', 'getKompileLocalStatus'
  ], { agents$: new Subject<AgentProvider[]>().asObservable() });
  const chatStorageServiceSpy = jasmine.createSpyObj('ChatStorageService', [
    'getSessions', 'saveSession', 'deleteSession', 'getSession'
  ]);
  const chatHistoryServiceSpy = jasmine.createSpyObj('ChatHistoryService', [
    'getSessions', 'createSession', 'getSession', 'addMessage',
    'getSessionMessages', 'deleteSession', 'updateSessionTitle', 'getMessageContent'
  ]);
  const cliTranscriptServiceSpy = jasmine.createSpyObj('CliTranscriptService', [
    'listSessions', 'discoverSources', 'getTranscript', 'getSyncStatus'
  ]);
  const folderServiceSpy = jasmine.createSpyObj('FolderService', [
    'getFolders', 'getFolderFiles', 'associateSession', 'disassociateSession'
  ], {
    folders$: new Subject<any[]>().asObservable(),
    selectedFolder$: new Subject<any>().asObservable()
  });
  const modelContextServiceSpy = jasmine.createSpyObj('ModelContextService', [
    'refresh', 'getStagingModelCardUrl'
  ], {
    context$: new Subject<any>().asObservable(),
    loading$: new Subject<boolean>().asObservable(),
    backendUrl: '/api'
  });
  const webSocketServiceSpy = jasmine.createSpyObj('WebSocketService', [
    'getMonitorEvents', 'connect', 'disconnect', 'subscribeToMonitor', 'unsubscribeFromMonitor'
  ]);
  webSocketServiceSpy.subscribeToMonitor.and.returnValue(new Subject<any>().asObservable());
  const ragServiceSpy = jasmine.createSpyObj('ConversationalRagService', ['getStatus']);
  ragServiceSpy.getStatus.and.returnValue(of({ available: false, service: '' }));
  const dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);

  agentChatServiceSpy.getStreamingContent.and.returnValue(new Subject<string>().asObservable());
  agentChatServiceSpy.getStreamingComplete.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getStreamingError.and.returnValue(new Subject<string>().asObservable());
  agentChatServiceSpy.getChatStats.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getSources.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getFilesModified.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getToolUse.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getCompaction.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.sendMessage.and.returnValue(Promise.resolve());
  agentChatServiceSpy.createSession.and.returnValue({
    id: 'agent-session-1', name: 'Test Session', messages: [],
    createdAt: new Date().toISOString()
  });
  agentServiceSpy.getAllAgents.and.returnValue(of([]));
  agentServiceSpy.getAvailableAgents.and.returnValue(of([]));
  agentServiceSpy.getChatHarnessAgents.and.returnValue(of([]));
  agentServiceSpy.refreshChatHarnessAgents.and.returnValue(of([]));
  chatStorageServiceSpy.getSessions.and.returnValue([]);
  chatHistoryServiceSpy.getSessions.and.returnValue(of([]));
  cliTranscriptServiceSpy.listSessions.and.returnValue(of([]));
  cliTranscriptServiceSpy.discoverSources.and.returnValue(of({}));
  cliTranscriptServiceSpy.getSyncStatus.and.returnValue(of({
    running: false, sourceIndex: 0, totalSources: 0, sourcePending: 0, sourceImported: 0
  } as any));
  folderServiceSpy.getFolders.and.returnValue(of([]));

  return {
    providers: [
      { provide: ConversationalRagService, useValue: ragServiceSpy },
      { provide: LocalAgentChatService, useValue: agentChatServiceSpy },
      { provide: AgentService, useValue: agentServiceSpy },
      { provide: ChatStorageService, useValue: chatStorageServiceSpy },
      { provide: ChatHistoryService, useValue: chatHistoryServiceSpy },
      { provide: CliTranscriptService, useValue: cliTranscriptServiceSpy },
      { provide: FolderService, useValue: folderServiceSpy },
      { provide: ModelContextService, useValue: modelContextServiceSpy },
      { provide: WebSocketService, useValue: webSocketServiceSpy },
      { provide: MatDialog, useValue: dialogSpy }
    ]
  };
}

// Real component send/completion subscriptions and real SSE transport; no lifecycle polling.
describe('UnifiedChat command outcomes', () => {
  let component: UnifiedChatComponent;
  let service: LocalAgentChatService;
  let send: jasmine.Spy;
  let fetchSpy: jasmine.Spy;
  let savedStorage: string | null;
  const agent = { name: 'coder', displayName: 'Coder' } as AgentProvider;
  const event = (name: string, data: unknown) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
  const response = (events: string) => new Response(new ReadableStream<Uint8Array>({
    start(controller) {
      const bytes = new TextEncoder().encode(events);
      controller.enqueue(bytes.slice(0, 37));
      controller.enqueue(bytes.slice(37));
      controller.close();
    }
  }), { status: 200 });

  beforeEach(() => {
    savedStorage = localStorage.getItem('unified_chat_sessions');
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [LocalAgentChatService, {
        provide: ChatStorageService,
        useValue: jasmine.createSpyObj('storage', ['updateSession', 'updateTab'])
      }]
    });
    service = TestBed.inject(LocalAgentChatService);
    const unused = null as any;
    const cdr = jasmine.createSpyObj('cdr', ['detach', 'reattach', 'detectChanges', 'markForCheck']);
    component = new UnifiedChatComponent(
      unused, service, unused, unused, unused, unused, unused, unused, unused,
      unused, unused, unused, cdr, unused, unused, unused, unused, unused
    );
    spyOn<any>(component, 'updateMonitorSubscription');
    spyOn<any>(component, 'refreshContextBudget');
    component.selectedAgent = agent;
    component.agents = [agent];
    component.newChat();
    send = spyOn(service, 'sendMessage').and.callThrough();
    fetchSpy = spyOn(window, 'fetch');
  });

  afterEach(() => {
    (component as any).cleanupStreaming();
    (component as any).unsubscribeStreamingSubs();
    if (savedStorage === null) localStorage.removeItem('unified_chat_sessions');
    else localStorage.setItem('unified_chat_sessions', savedStorage);
  });

  for (const [command, status] of [
    ['/help', 'COMPLETED'], ['/missing', 'UNKNOWN_COMMAND'],
    ['/login', 'TERMINAL_REQUIRED'], ['/resume', 'LIVE_SESSION_REQUIRED'],
    ['/unsupported', 'NOT_YET_SUPPORTED']
  ]) {
    it(`persists ${command} ${status} as system output and excludes it from subsequent context`, async () => {
      const outcome: CommandOutcome = {
        command, status, text: `CLI ${status}`, ok: status === 'COMPLETED',
        exit: status === 'COMPLETED' ? 0 : 2
      };
      fetchSpy.and.resolveTo(response(event('command', outcome)
        + event('complete', { content: outcome.text, commandOutcome: outcome })));
      component.userInput = command;
      component.sendMessage();
      await send.calls.mostRecent().returnValue;
      expect(component.isStreaming).toBeFalse();
      expect(component.messages.length).toBe(2);
      const [input, output] = component.messages;
      expect(input.commandOnly).toBeTrue();
      expect(input.commandOutcome).toEqual(outcome);
      expect(output.role).toBe('system');
      expect(output.kind).toBe('notice');
      expect(output.content).toBe(outcome.text);
      expect(output.commandOnly).toBeTrue();
      expect(output.commandOutcome).toEqual(outcome);
      expect(output.agent).toBeUndefined();
      expect(output.tokenMetrics).toBeUndefined();
      expect(output.isStreaming).toBeFalse();
      expect(output.error).not.toBeTrue();
      expect(component.estimatedContextTokens).toBe(0);
      const compact = spyOn(service, 'compactChat');
      await component.compactContext(false);
      expect(compact).not.toHaveBeenCalled();

      // Reload the persisted visible transcript and the independently stored wire session.
      const wire = JSON.parse(JSON.stringify(component['agentSession'])) as LocalAgentSession;
      const sessions = JSON.parse(localStorage.getItem('unified_chat_sessions')!);
      component.loadSession(sessions[0]);
      expect(component.messages[0].commandOnly).toBeTrue();
      expect(component.messages[1].commandOutcome).toEqual(outcome);
      expect(component.messages[1].role).toBe('system');
      expect(component.messages[1].agent).toBeUndefined();
      component['agentSession'] = wire;
      const raw = '  /custom-skill  first\n second  \n';
      fetchSpy.and.resolveTo(response(event('complete', { content: 'model answer' })));
      component.userInput = raw;
      component.sendMessage();
      await send.calls.mostRecent().returnValue;
      let request = JSON.parse(String(fetchSpy.calls.mostRecent().args[1].body));
      expect(request.message).toBe(raw);
      expect(request.chatHistory).toEqual([]);
      expect(component.messages[2].content).toBe(raw);
      expect(component.messages[2].commandOnly).not.toBeTrue();
      expect(component.messages[3].role).toBe('assistant');
      expect(component.messages[3].agent).toBe(agent);
      fetchSpy.and.resolveTo(response(event('complete', { content: 'next answer' })));
      component.userInput = 'follow up';
      component.sendMessage();
      await send.calls.mostRecent().returnValue;
      request = JSON.parse(String(fetchSpy.calls.mostRecent().args[1].body));
      expect(request.chatHistory).toEqual([
        { role: 'USER', content: raw }, { role: 'ASSISTANT', content: 'model answer' }
      ]);
    });
  }

  it('uses trim only to reject blank input', () => {
    component.userInput = ' \n\t ';
    component.sendMessage();
    expect(send).not.toHaveBeenCalled();
    expect(component.messages).toEqual([]);
  });

  describe('/model menu', () => {
    const modelMenu: CommandEventData = {
      menu: 'model',
      provider: 'custom',
      currentModel: 'm-large',
      liveListingAvailable: true,
      note: 'offline snapshot',
      models: [
        { id: 'm-small', display: 'Small', contextLimit: 8192 },
        { id: 'm-large', contextLimit: 32768, current: true }
      ]
    };
    const menuOutcome: CommandOutcome = {
      command: '/model', status: 'INTERACTION_REQUIRED',
      text: 'Available models for provider \'custom\'.', ok: true, exit: 0,
      data: modelMenu
    };
    const appliedOutcome: CommandOutcome = {
      command: '/model', status: 'INTERACTION_REQUIRED',
      text: 'Model selection saved for this session: m-small', ok: true, exit: 0,
      data: { state: { sessionId: 'web-abc', workingDirectory: '/project', model: 'm-small' } }
    };
    const invalidOutcome: CommandOutcome = {
      command: '/model', status: 'INVALID',
      text: "Unknown model: 'nope' is not in the provider's known catalog.",
      ok: false, exit: 2
    };

    it('renders the picker from a mocked command SSE and labels entries with id, display, and context', async () => {
      fetchSpy.and.resolveTo(response(event('command', menuOutcome)
        + event('complete', { content: menuOutcome.text, commandOutcome: menuOutcome })));
      component.userInput = '/model';
      component.sendMessage();
      await send.calls.mostRecent().returnValue;

      expect(component.messages.length).toBe(2);
      expect(component.modelMenu).toEqual(modelMenu);
      expect(component.messages[1].commandOutcome?.data?.menu).toBe('model');
      // Entries carry the full menu payload; the current one is flagged.
      expect(component.modelMenu!.models!.map(m => component.modelMenuLabel(m)))
        .toEqual(['Small', 'm-large']);
      expect(component.modelMenu!.models!.find(m => m.id === 'm-large')?.current).toBeTrue();
    });

    it('selecting an option sends the raw "/model <id>" text through the same send path', async () => {
      fetchSpy.and.resolveTo(response(event('command', menuOutcome)
        + event('complete', { content: menuOutcome.text, commandOutcome: menuOutcome })));
      component.userInput = '/model';
      component.sendMessage();
      await send.calls.mostRecent().returnValue;

      fetchSpy.and.resolveTo(response(event('command', appliedOutcome)
        + event('complete', { content: appliedOutcome.text, commandOutcome: appliedOutcome })));
      component.selectModel('m-small');
      expect(component.userInput).toBe(''); // consumed by sendMessage()
      await send.calls.mostRecent().returnValue;

      const request = JSON.parse(String(fetchSpy.calls.mostRecent().args[1].body));
      expect(request.message).toBe('/model m-small');
      const outcome = component.messages[3].commandOutcome!;
      expect(outcome.status).toBe('INTERACTION_REQUIRED');
      expect(outcome.data?.state?.model).toBe('m-small');
      // The raw command line stays in the visible transcript...
      expect(component.messages[2].content).toBe('/model m-small');
      // ...but never becomes model context.
      expect(component.estimatedContextTokens).toBe(0);
    });

    it('marks command turns command-only so they cannot pollute model history', async () => {
      fetchSpy.and.resolveTo(response(event('complete', { content: 'warm up' })));
      component.userInput = 'warm up';
      component.sendMessage();
      await send.calls.mostRecent().returnValue;

      fetchSpy.and.resolveTo(response(event('command', menuOutcome)
        + event('complete', { content: menuOutcome.text, commandOutcome: menuOutcome })));
      component.userInput = '/model';
      component.sendMessage();
      await send.calls.mostRecent().returnValue;

      fetchSpy.and.resolveTo(response(event('command', appliedOutcome)
        + event('complete', { content: appliedOutcome.text, commandOutcome: appliedOutcome })));
      component.selectModel('m-small');
      await send.calls.mostRecent().returnValue;

      // Only the genuine model turn survives in the wire history.
      const history = JSON.parse(String(fetchSpy.calls.mostRecent().args[1].body)).chatHistory;
      expect(history).toEqual([
        { role: 'USER', content: 'warm up' }, { role: 'ASSISTANT', content: 'warm up' }
      ]);
      // Both command turns carry the commandOnly markers (user + system each).
      const prior = component.messages.slice(0, -2);
      expect(prior.filter(m => m.role === 'user' && m.commandOnly).length).toBe(1);
      expect(prior.filter(m => m.role === 'system' && m.commandOnly).length).toBe(1);
      expect(component.messages[4].commandOnly).toBeTrue();
      expect(component.messages[5].commandOnly).toBeTrue();
    });

    it('INVALID outcomes show status text only, with no picker', async () => {
      fetchSpy.and.resolveTo(response(event('command', invalidOutcome)
        + event('complete', { content: invalidOutcome.text, commandOutcome: invalidOutcome })));
      component.userInput = '/model nope';
      component.sendMessage();
      await send.calls.mostRecent().returnValue;

      expect(component.messages.length).toBe(2);
      expect(component.messages[1].role).toBe('system');
      expect(component.messages[1].content).toContain("Unknown model: 'nope'");
      expect(component.modelMenu).toBeNull();
    });

    it('selection is refused while a turn streams', async () => {
      let release!: (r: Response) => void;
      const pending = new Promise<Response>(resolve => { release = resolve; });
      fetchSpy.and.returnValue(pending);

      component.userInput = '/model';
      component.sendMessage();
      expect(component.isStreaming).toBeTrue();
      component.selectModel('m-small'); // must be ignored by the streaming guard
      expect(send).toHaveBeenCalledTimes(1);

      release(response(event('command', menuOutcome)
        + event('complete', { content: menuOutcome.text, commandOutcome: menuOutcome })));
      await send.calls.mostRecent().returnValue;
      expect(component.isStreaming).toBeFalse();
      component.selectModel('m-small'); // streaming done → dispatches now
      expect(send).toHaveBeenCalledTimes(2);
      await send.calls.mostRecent().returnValue;
    });

    it('applies data.state.model to the existing current-model display', async () => {
      (component as any).contextBudget = {
        agentName: 'coder', model: 'm-large', contextWindow: 32768,
        maxOutputTokens: 4096, inputBudgetTokens: 24576,
        source: 'kompile-cli-main', compactTriggerRatio: 0.85
      };
      fetchSpy.and.resolveTo(response(event('command', appliedOutcome)
        + event('complete', { content: appliedOutcome.text, commandOutcome: appliedOutcome })));
      component.userInput = '/model m-small';
      component.sendMessage();
      await send.calls.mostRecent().returnValue;

      expect((component as any).contextBudget.model).toBe('m-small');
    });
  });
});

// DOM-level checks: the menu renders inside a real fixture and its select
// honors the streaming disable. Harness mirrors unified-chat-rag.spec.ts.
describe('UnifiedChat /model menu rendering', () => {
  let fixture: ComponentFixture<UnifiedChatComponent>;
  let component: UnifiedChatComponent;
  let spies: ReturnType<typeof createMenuTestBed>;
  let savedStorage: string | null;

  const menuOutcome: CommandOutcome = {
    command: '/model', status: 'INTERACTION_REQUIRED',
    text: 'Available models.', ok: true, exit: 0,
    data: {
      menu: 'model', provider: 'custom', currentModel: 'm-large',
      models: [{ id: 'm-small', display: 'Small', contextLimit: 8192 },
        { id: 'm-large', contextLimit: 32768, current: true }]
    }
  };

  function pushMenuOutcome(): void {
    const message = {
      id: 'msg-' + Date.now(), role: 'SYSTEM' as const, content: menuOutcome.text,
      timestamp: new Date().toISOString(), streaming: false,
      commandOnly: true, commandOutcome: menuOutcome
    };
    component.messages.push({
      id: 'ui-' + message.id, role: 'system', kind: 'notice',
      content: message.content, timestamp: new Date(),
      commandOnly: true, commandOutcome: menuOutcome
    });
  }

  beforeEach(async () => {
    savedStorage = localStorage.getItem('unified_chat_sessions');
    spies = createMenuTestBed();
    await TestBed.configureTestingModule({
      imports: [FormsModule, NoopAnimationsModule, HttpClientTestingModule, MatMenuModule],
      declarations: [UnifiedChatComponent],
      providers: spies.providers,
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();
    fixture = TestBed.createComponent(UnifiedChatComponent);
    component = fixture.componentInstance;
    // Mirrors unified-chat-rag.spec.ts: set the persona BEFORE the first
    // detectChanges so ngOnInit resolves the selected harness agent and the
    // composer/messages region renders instead of the no-agents warning.
    component.selectedAgent = { name: 'coder', displayName: 'Coder' } as AgentProvider;
  });

  afterEach(() => {
    if (savedStorage === null) localStorage.removeItem('unified_chat_sessions');
    else localStorage.setItem('unified_chat_sessions', savedStorage);
  });

  it('renders the model picker with entries and flags the current model', () => {
    fixture.detectChanges();
    pushMenuOutcome();
    // OnPush: pushMenuOutcome mutates messages outside the CD cycle, so mark
    // the component's view dirty (same pattern as component internals) before
    // detectChanges re-checks it.
    (component as any).cdr.markForCheck();
    fixture.detectChanges();

    const menu = fixture.nativeElement.querySelector('[data-testid="model-menu"]');
    expect(menu).not.toBeNull();
    const select = menu.querySelector('select');
    expect(select).not.toBeNull();
    expect(select.disabled).toBeFalse();
    const options = Array.from(select.querySelectorAll('option')) as HTMLOptionElement[];
    const labels = options.slice(1).map(o => o.textContent!.trim());
    expect(labels[0]).toContain('Small');
    expect(labels[0]).toContain('8192');
    expect(labels[1]).toContain('m-large');
    expect(labels[1]).toContain('current');
  });

  it('disables the picker and shows a busy placeholder while streaming', () => {
    fixture.detectChanges();
    component.isStreaming = true;
    pushMenuOutcome();
    // OnPush: mark dirty so the streaming placeholder/disabled re-render runs.
    (component as any).cdr.markForCheck();
    fixture.detectChanges();

    const select = fixture.nativeElement
      .querySelector('[data-testid="model-menu"] select') as HTMLSelectElement;
    expect(select.disabled).toBeTrue();
    expect(select.textContent).toContain('Working');
  });
});
