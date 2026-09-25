import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
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
import { CommandConfigDialogComponent, CommandConfigDialogData } from '../command-config-dialog/command-config-dialog.component';

/** Fixture-mounted TestBed harness (mirrors unified-chat-rag.spec.ts) for DOM assertions. */
function createMenuTestBed() {
  const agentChatServiceSpy = jasmine.createSpyObj('LocalAgentChatService', [
    'getStreamingContent', 'getStreamingComplete', 'getStreamingError',
    'getChatStats', 'getSources', 'getFilesModified', 'sendMessage',
    'cancelStreaming', 'createSession', 'getToolUse', 'getCompaction', 'getContextBudget',
    'getCommandOutcomes'
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
  agentServiceSpy.getChatHarnessAgents.and.returnValue(of([
    { name: 'coder', displayName: 'Coder', available: true, agentType: 'HARNESS' } as AgentProvider
  ]));
  agentChatServiceSpy.getContextBudget.and.returnValue(of(null));
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
    // dialog is ctor param 16 (ragService..sanitizer then dialog): a spy so
    // /clear hand-off assertions can observe closeAll.
    const dialogSpy = jasmine.createSpyObj('MatDialog', ['open', 'closeAll']);
    component = new UnifiedChatComponent(
      unused, service, unused, unused, unused, unused, unused, unused, unused,
      unused, unused, unused, cdr, unused, unused, dialogSpy, unused, unused, unused
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

  it('renders and persists each live turn separately without duplicating terminal text', async () => {
    fetchSpy.and.resolveTo(response(event('start', { processId: 'harness-turns' })
      + event('turn_started', { turnId: 1, source: 'initial', text: '' })
      + event('chunk', 'first answer') + event('turn_complete', { turnId: 1, text: 'first answer' })
      + event('turn_started', { turnId: 2, source: 'user', text: 'follow up' })
      + event('chunk', 'second answer') + event('turn_complete', { turnId: 2, text: 'second answer' })
      + event('complete', { content: 'second answer' })));
    component.userInput = 'original'; component.sendMessage();
    await send.calls.mostRecent().returnValue;
    expect(component.messages.map(message => message.content)).toEqual(['original', 'first answer', 'follow up', 'second answer']);
    expect(component.messages.map(message => message.role)).toEqual(['user', 'assistant', 'user', 'assistant']);
    expect(component.messages.every(message => !message.isStreaming)).toBeTrue();
    expect(component.currentSession?.messages.length).toBe(4);
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
      expect(component.messages[1].commandOutcome?.data).toEqual(modelMenu);
      expect(component.messages[1].commandOutcome?.data?.menu).toBe('model');
      // Entries carry the full menu payload; the current one is flagged.
      expect(component.messages[1].commandOutcome?.data?.models!.map(m => m.display || m.id))
        .toEqual(['Small', 'm-large']);
      expect(component.messages[1].commandOutcome?.data?.models!.find(m => m.id === 'm-large')?.current)
        .toBeTrue();
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
      expect(component.messages[1].commandOutcome?.data?.menu).toBeUndefined();
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
      // A second fetch needs a fresh body; reusing the consumed response hid a stream failure.
      fetchSpy.and.resolveTo(response(event('command', appliedOutcome)
        + event('complete', { content: appliedOutcome.text, commandOutcome: appliedOutcome })));
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

    it('armed /clear dispatch resolves and starts a fresh chat', async () => {
      const clearOutcome: CommandOutcome = {
        command: '/clear', status: 'INTERACTION_REQUIRED', text: 'Starting a new conversation.',
        ok: true, exit: 0, data: { menu: 'clear', sessionId: 'web-abc' }
      };
      fetchSpy.and.resolveTo(response(event('command', clearOutcome)
        + event('complete', { content: clearOutcome.text, commandOutcome: clearOutcome })));
      const newChat = spyOn(component, 'newChat');
      // closeAll is already a jasmine spy from the ctor's createSpyObj.
      const closeAll = (component as any).dialog.closeAll as jasmine.Spy;
      (component as any).performConversationClear();
      expect(component.clearOutcomeArmed).toBeTrue();
      component.userInput = '/clear';
      component.sendMessage();
      await send.calls.mostRecent().returnValue;

      expect(component.clearOutcomeArmed).toBeFalse();
      expect(closeAll).toHaveBeenCalled();
      expect(newChat).toHaveBeenCalled();
    });

    it('an unarmed clear outcome does not restart the chat', async () => {
      const clearOutcome: CommandOutcome = {
        command: '/clear', status: 'INTERACTION_REQUIRED', text: 'Starting a new conversation.',
        ok: true, exit: 0, data: { menu: 'clear' }
      };
      fetchSpy.and.resolveTo(response(event('command', clearOutcome)
        + event('complete', { content: clearOutcome.text, commandOutcome: clearOutcome })));
      const newChat = spyOn(component, 'newChat');
      component.userInput = '/clear';
      component.sendMessage();
      await send.calls.mostRecent().returnValue;
      expect(newChat).not.toHaveBeenCalled();
    });
  });
});

// DOM-level checks: the menu renders inside a real fixture and its select
// honors the streaming disable. Harness mirrors unified-chat-rag.spec.ts.
describe('UnifiedChat session configuration modal', () => {
  let fixture: ComponentFixture<UnifiedChatComponent>;
  let component: UnifiedChatComponent;
  let spies: ReturnType<typeof createMenuTestBed>;
  let savedStorage: string | null;

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
    // The agent-list mock also supplies this persona, so ngOnInit keeps the
    // composer enabled rather than clearing selectedAgent on an empty list.
    component.selectedAgent = { name: 'coder', displayName: 'Coder' } as AgentProvider;
  });

  afterEach(() => {
    if (savedStorage === null) localStorage.removeItem('unified_chat_sessions');
    else localStorage.setItem('unified_chat_sessions', savedStorage);
  });

  it('binds document Ctrl+B and renders safe process activity controls while the composer is live', () => {
    fixture.detectChanges();
    component.isStreaming = true;
    const service = TestBed.inject(LocalAgentChatService);
    service.liveControlsReady = true;
    service.harnessActivity = { backgroundable: true, turnActive: true,
      tasks: [], processes: [{ id: 'proc-1', description: 'build', state: 'RUNNING', output: '<script>unsafe</script>' }] };
    const send = spyOn(component, 'sendHarnessControl').and.resolveTo();
    (component as any).cdr.markForCheck();
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('[data-testid="chat-input"]') as HTMLTextAreaElement;
    expect(input.disabled).toBeFalse();
    const event = new KeyboardEvent('keydown', { key: 'b', ctrlKey: true, bubbles: true, cancelable: true });
    document.dispatchEvent(event);
    expect(event.defaultPrevented).toBeTrue();
    expect(send).toHaveBeenCalledOnceWith('background');
    const panel = fixture.nativeElement.querySelector('[data-testid="harness-activity"]');
    expect(panel.textContent).toContain('<script>unsafe</script>');
    expect(panel.querySelector('script')).toBeNull();
    const stop = Array.from(panel.querySelectorAll('button')).find((b: any) => b.textContent.includes('Stop process')) as HTMLButtonElement;
    stop.click();
    expect(send).toHaveBeenCalledWith('process_kill', 'proc-1');
  });

  it('retains child input DOM across snapshots and sends only to the selected child', () => {
    fixture.detectChanges();
    component.isStreaming = true;
    const service = TestBed.inject(LocalAgentChatService);
    service.liveControlsReady = true;
    const child = { id: 'child-1', type: 'coder', description: 'review', state: 'thinking', running: true, canSend: true, canCancel: true };
    service.harnessActivity = { backgroundable: false, turnActive: false, tasks: [], processes: [], subagents: [child] };
    (component as any).cdr.markForCheck(); fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('[data-testid="subagent-activity"] textarea') as HTMLTextAreaElement;
    input.value = 'child follow-up';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    service.harnessActivity = { ...service.harnessActivity, subagents: [{ ...child, output: 'updated output' }] };
    (component as any).cdr.markForCheck(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="subagent-activity"] textarea')).toBe(input);
    expect(input.value).toBe('child follow-up');
    const send = spyOn(component, 'sendHarnessControl').and.resolveTo();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    expect(send).toHaveBeenCalledWith('subagent_input', 'child-1', 'child follow-up');
    const summary = fixture.nativeElement.querySelector('[data-testid="subagent-activity"] summary');
    summary.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete', bubbles: true, cancelable: true }));
    expect(send).toHaveBeenCalledWith('subagent_cancel', 'child-1');
  });

  function typeInput(value: string): HTMLTextAreaElement {
    const input = fixture.nativeElement.querySelector('[data-testid="chat-input"]') as HTMLTextAreaElement;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    return input;
  }

  function key(input: HTMLTextAreaElement, name: string, options: KeyboardEventInit = {}): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true, ...options });
    input.dispatchEvent(event);
    fixture.detectChanges();
    return event;
  }

  const slashMenu = (f: ComponentFixture<UnifiedChatComponent>) =>
    f.nativeElement.querySelector('[data-testid="slash-command-menu"]') as HTMLElement | null;

  it('opens common command suggestions on bare slash and filters case-insensitively', () => {
    fixture.detectChanges();
    const input = typeInput('/');
    expect(slashMenu(fixture)).not.toBeNull();
    expect(slashMenu(fixture)!.querySelectorAll('[role="option"]').length).toBe(component.slashCommands.length);
    expect(input.getAttribute('aria-expanded')).toBe('true');
    expect(component.slashCommands.map(item => item.command)).toEqual(['/help', '/model', '/role', '/fast', '/skills']);
    typeInput('/Mo');
    const options = slashMenu(fixture)!.querySelectorAll('[role="option"]');
    expect(options.length).toBe(1);
    expect(options[0].textContent).toContain('/model');
    expect(input.getAttribute('aria-activedescendant')).toBe(options[0].id);
  });

  it('wraps arrow navigation, resets selection on filtering and completes with Enter without sending', () => {
    fixture.detectChanges();
    const send = spyOn(component, 'sendMessage');
    const input = typeInput('/');
    expect(key(input, 'ArrowUp').defaultPrevented).toBeTrue();
    expect(component.slashSelectedIndex).toBe(component.slashCommands.length - 1);
    key(input, 'ArrowDown');
    expect(component.slashSelectedIndex).toBe(0);
    key(input, 'ArrowDown');
    expect(component.slashSelectedIndex).toBe(1);
    typeInput('/mo');
    expect(component.slashSelectedIndex).toBe(0);
    expect(key(input, 'Enter').defaultPrevented).toBeTrue();
    expect(component.userInput).toBe('/model ');
    expect(slashMenu(fixture)).toBeNull();
    expect(send).not.toHaveBeenCalled();
    key(input, 'Enter');
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('completes with Tab or pointer selection without executing a command', () => {
    fixture.detectChanges();
    const send = spyOn(component, 'sendMessage');
    const input = typeInput('/ski');
    expect(key(input, 'Tab').defaultPrevented).toBeTrue();
    expect(component.userInput).toBe('/skills ');
    typeInput('/mo');
    const option = slashMenu(fixture)!.querySelector('button')!;
    const down = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    option.dispatchEvent(down);
    expect(down.defaultPrevented).toBeTrue(); // keep textarea focus; blur must not eat the click
    option.click();
    fixture.detectChanges();
    expect(component.userInput).toBe('/model ');
    expect(slashMenu(fixture)).toBeNull();
    expect(send).not.toHaveBeenCalled();
  });

  it('dismisses on Escape before stopping a live run, and on blur', () => {
    fixture.detectChanges();
    component.isStreaming = true;
    TestBed.inject(LocalAgentChatService).liveControlsReady = true;
    const cancel = spyOn(component, 'cancelStreaming');
    const input = typeInput('/');
    expect(key(input, 'Escape').defaultPrevented).toBeTrue();
    expect(slashMenu(fixture)).toBeNull();
    expect(component.userInput).toBe('/');
    expect(cancel).not.toHaveBeenCalled();
    key(input, 'Escape');
    expect(cancel).toHaveBeenCalledTimes(1);
    typeInput('/');
    input.dispatchEvent(new FocusEvent('blur'));
    fixture.detectChanges();
    expect(slashMenu(fixture)).toBeNull();
  });

  it('leaves arguments, paths, multiline text, and unknown skills alone', () => {
    fixture.detectChanges();
    const send = spyOn(component, 'sendMessage');
    for (const value of ['', 'ordinary text', 'a /mo', '/model value', '/tmp/file', '/mo\ntext', '/custom-skill']) {
      typeInput(value);
      expect(slashMenu(fixture)).withContext(value).toBeNull();
      expect(component.userInput).toBe(value);
    }
    const input = typeInput('/custom-skill');
    key(input, 'Enter');
    expect(send).toHaveBeenCalledTimes(1);
    expect(component.userInput).toBe('/custom-skill');
  });

  it('preserves modified Enter, IME and repeat keys and hides suggestions when the composer is disabled', () => {
    fixture.detectChanges();
    const send = spyOn(component, 'sendMessage');
    const input = typeInput('/');
    for (const options of [{ shiftKey: true }, { ctrlKey: true }, { metaKey: true },
      { altKey: true }, { isComposing: true }, { repeat: true }]) {
      expect(key(input, 'Enter', options).defaultPrevented).toBeFalse();
    }
    expect(send).not.toHaveBeenCalled();
    expect(component.userInput).toBe('/');
    component.isLoading = true;
    typeInput('/');
    expect(slashMenu(fixture)).toBeNull();
    component.isLoading = false;
    component.isStreaming = true;
    typeInput('/');
    expect(slashMenu(fixture)).toBeNull();
    component.isStreaming = false;
    component.selectedAgent = null;
    typeInput('/');
    expect(slashMenu(fixture)).toBeNull();
  });

  it('opens the Session Configuration modal; the dialog loads quietly over HTTP, not by sending messages', async () => {
    fixture.detectChanges();
    const open = spyOn(component, 'openCommandConfig').and.callThrough();
    const dialogSpy = TestBed.inject(MatDialog) as jasmine.SpyObj<MatDialog>;
    dialogSpy.open.and.returnValue({ close: () => undefined } as any);
    const button = fixture.nativeElement.querySelector('[data-testid="command-config-open"]') as HTMLButtonElement;
    expect(button).not.toBeNull();
    button.click();
    expect(open).toHaveBeenCalled();
    expect(dialogSpy.open).toHaveBeenCalled();
    // No dispatch happens on open — the dialog fetches its snapshot in the
    // background; sending only occurs for an explicit user action.
    const send = spyOn(component, 'sendMessage');
    const data = dialogSpy.open.calls.mostRecent().args[1]?.data as CommandConfigDialogData;
    data.dispatch('/model m1');
    expect(component.userInput).toBe('/model m1');
    expect(send).toHaveBeenCalled();
  });

  it('dialog shows only a loading state until the quiet snapshot resolves, then populates every section', async () => {
    const bus = new Subject<CommandOutcome>();
    const snapshot: any = {
      menu: 'config', model: { menu: 'model', models: [{ id: 'm1', current: true }] },
      role: { menu: 'role', roles: [{ name: 'architect' }] },
      fast: { menu: 'fast', fastMode: false, supported: true },
      reminders: { menu: 'reminders', scope: 'session', reminders: [{ text: 'r1' }] },
      queue: { menu: 'queue', queued: [] }
    };
    const data: CommandConfigDialogData = {
      modelMenu: null, roleMenu: null, fastMenu: null,
      reminders: null, remindersGlobal: null, loops: null, loopsGlobal: null,
      queue: null,
      continueMenu: null,
      judgeMenu: null,
      sessionId: 'browser-session-1',
      busy: () => false,
      liveSession: () => false,
      dispatch: () => undefined,
      selectModel: () => undefined,
      selectRole: () => undefined,
      toggleFastMode: () => undefined,
      clearConversation: () => undefined
    };
    // The dialog owns the quiet snapshot fetch; stub the service methods it
    // uses (these tests build the dialog directly against a stub service).
    const snapshot$ = new Subject<any>();
    let requestedSessionId: string | undefined;
    const svc = {
      getCommandOutcomes: () => bus.asObservable(),
      getSessionConfig: (sessionId?: string) => {
        requestedSessionId = sessionId;
        return snapshot$.asObservable();
      }
    } as unknown as LocalAgentChatService;
    const dialog = new CommandConfigDialogComponent(
      { close: () => undefined } as any, data, svc);
    dialog.ngOnInit();
    expect(requestedSessionId).toBe('browser-session-1');
    expect(dialog.loading).toBeTrue();
    expect(dialog.modelMenu).toBeNull();
    snapshot$.next(snapshot);
    snapshot$.complete();
    expect(dialog.loading).toBeFalse();
    expect(dialog.loadError).toBeNull();
    expect(dialog.modelMenu?.models?.[0].id).toBe('m1');
    expect(dialog.roleMenu?.roles?.[0].name).toBe('architect');
    expect(dialog.fastMenu?.supported).toBeTrue();
    expect(dialog.reminders?.reminders?.[0].text).toBe('r1');
    expect(dialog.queue?.queued?.length).toBe(0);
    dialog.ngOnDestroy();
  });

  it('a failed quiet snapshot surfaces an error state instead of dispatching commands', async () => {
    const bus = new Subject<CommandOutcome>();
    const data: CommandConfigDialogData = {
      modelMenu: null, roleMenu: null, fastMenu: null,
      reminders: null, remindersGlobal: null, loops: null, loopsGlobal: null,
      queue: null,
      continueMenu: null,
      judgeMenu: null,
      busy: () => false,
      liveSession: () => false,
      dispatch: () => undefined,
      selectModel: () => undefined,
      selectRole: () => undefined,
      toggleFastMode: () => undefined,
      clearConversation: () => undefined
    };
    const svc = {
      getCommandOutcomes: () => bus.asObservable(),
      getSessionConfig: () => {
        const failed = new Subject<any>();
        failed.error({ message: 'harness unavailable' });
        return failed.asObservable();
      }
    } as unknown as LocalAgentChatService;
    const dialog = new CommandConfigDialogComponent(
      { close: () => undefined } as any, data, svc);
    dialog.ngOnInit();
    await Promise.resolve();
    expect(dialog.loading).toBeFalse();
    expect(dialog.loadError).toBe('harness unavailable');
    expect(dialog.modelMenu).toBeNull();
    dialog.ngOnDestroy();
  });

  it('command outcomes update the modal data in place via the outcome bus, not only the transcript', async () => {
    const bus = new Subject<CommandOutcome>();
    const data: CommandConfigDialogData = {
      modelMenu: null, roleMenu: null, fastMenu: null,
      reminders: null, remindersGlobal: null, loops: null, loopsGlobal: null,
      queue: null,
      continueMenu: null,
      judgeMenu: null,
      busy: () => false,
      liveSession: () => false,
      dispatch: () => undefined,
      selectModel: () => undefined,
      selectRole: () => undefined,
      toggleFastMode: () => undefined,
      clearConversation: () => undefined
    };
    const svc = {
      getCommandOutcomes: () => bus.asObservable(),
      getSessionConfig: () => of({ menu: 'config' } as any)
    } as unknown as LocalAgentChatService;
    const dialog = new CommandConfigDialogComponent(
      { close: () => undefined } as any, data, svc);
    dialog.ngOnInit();
    await Promise.resolve();
    expect(dialog.modelMenu).toBeNull();

    bus.next({
      command: '/model', status: 'INTERACTION_REQUIRED', text: 'models', ok: true, exit: 0,
      data: { menu: 'model', provider: 'custom', models: [{ id: 'm1', current: true }] }
    });
    expect(dialog.modelMenu?.models?.length).toBe(1);
    expect(dialog.modelMenu?.models?.[0].current).toBeTrue();
    dialog.ngOnDestroy();
  });

  it('senders route through the same select methods as the modal', () => {
    fixture.detectChanges();
    const send = spyOn(component, 'sendMessage');
    component.selectModel('m-small');
    expect(component.userInput).toBe('/model m-small');
    component.selectRole('architect');
    expect(component.userInput).toBe('/role architect');
    component.toggleFastMode(true);
    expect(component.userInput).toBe('/fast on');
    expect(send).toHaveBeenCalledTimes(3);
  });
});
