import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import {
  InsightsSettingsView, InsightsTopicReport, LocalAgentChatService, SessionInsightsSnapshot
} from './local-agent-chat.service';
import { ChatStorageService } from './chat-storage.service';
import { SKIP_ERROR_SNACKBAR } from './http-error.interceptor';
import { AgentProvider, LocalAgentSession, MessageAttachment, ToolUseEvent } from '../models/api-models';

describe('LocalAgentChatService harness transport', () => {
  let service: LocalAgentChatService;
  let storage: jasmine.SpyObj<ChatStorageService>;

  beforeEach(() => {
    storage = jasmine.createSpyObj('ChatStorageService', ['updateSession', 'updateTab']);
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        LocalAgentChatService,
        { provide: ChatStorageService, useValue: storage }
      ]
    });
    service = TestBed.inject(LocalAgentChatService);
  });

  it('routes title metadata to its browser session without adding messages', async () => {
    const sse = (type: string, data: unknown) => `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
    spyOn(window, 'fetch').and.resolveTo(new Response(new TextEncoder().encode(
      sse('harness_session', { session_id: 'harness-title' })
      + sse('title', { session_id: 'wrong-session', title: 'Ignore' })
      + sse('title', { session_id: 'harness-title', title: '' })
      + sse('title', { session_id: 'harness-title', title: 'Fix authentication' })
      + sse('chunk', 'answer') + sse('complete', { content: 'answer' })), { status: 200 }));
    const titles: { sessionId: string; title: string }[] = [];
    service.getSessionTitle().subscribe(title => titles.push(title));
    const session = service.createSession('New Chat');
    await service.sendMessage(session, 'Fix this login', { name: 'coder' } as AgentProvider,
      { sessionId: 'browser-title' });
    expect(titles).toEqual([{ sessionId: 'browser-title', title: 'Fix authentication' }]);
    expect(session.messages.map(message => message.content)).toEqual(['Fix this login', 'answer']);
  });

  it('does not automatically steal a run back from a replacement subscriber', async () => {
    const events = 'id: 1\nevent: queued\ndata: {"processId":"harness-replaced","reconnectable":true}\n\n'
      + 'event: superseded\ndata: {"message":"another view"}\n\n';
    const fetchSpy = spyOn(window, 'fetch').and.resolveTo(new Response(new TextEncoder().encode(events), { status: 200 }));
    const errors: string[] = [];
    service.getStreamingError().subscribe(value => errors.push(value));
    await service.sendMessage(service.createSession('replaced'), 'original', { name: 'coder' } as AgentProvider, { sessionId: 'replaced-browser' });
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(errors).toEqual(['Run connected in another view. Work continues there.']);
    expect(service.getReconnectBookmark('replaced-browser')).not.toBeNull();
    (service as any).forgetReconnectBookmark();
  });

  it('does not apply a throttled prior-turn delta to the next turn', fakeAsync(() => {
    const values: string[] = [];
    service.getStreamingContent().subscribe(value => values.push(value));
    const raw = (service as any).streamingContentRaw$;
    raw.next({ content: 'old', epoch: 0 });
    raw.next({ content: 'old pending delta', epoch: 0 });
    (service as any).resetContentBuffer();
    (service as any).streamingContent$.next('');
    tick(60);
    expect(values[values.length - 1]).toBe('');
    raw.next({ content: 'new', epoch: 1 }); tick(60);
    expect(values[values.length - 1]).toBe('new');
    expect(values).not.toContain('old pending delta');
  }));

  it('replays a broken connection without duplicate chunks, prompts or completions', async () => {
    const event = (id: number, name: string, data: unknown) => `id: ${id}\nevent: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
    const response = (text: string) => new Response(new TextEncoder().encode(text), { status: 200 });
    const fetchSpy = spyOn(window, 'fetch').and.returnValues(
      Promise.resolve(response(event(1, 'queued', { processId: 'harness-replay', reconnectable: true })
        + event(2, 'start', { processId: 'harness-replay' })
        + event(3, 'turn_started', { turnId: 1, source: 'initial', text: '' }) + event(4, 'chunk', 'first'))),
      Promise.resolve(response(event(4, 'chunk', 'first') + event(5, 'turn_complete', { turnId: 1, text: 'first' })
        + event(6, 'turn_started', { turnId: 2, source: 'user', text: 'follow up' }) + event(7, 'chunk', 'second')
        + event(8, 'turn_complete', { turnId: 2, text: 'second' }) + event(9, 'complete', { content: 'second' }))));
    const session = service.createSession('replay');
    const completions: unknown[] = [];
    service.getStreamingComplete().subscribe(value => completions.push(value));
    await service.sendMessage(session, 'original', { name: 'coder', displayName: 'Coder' } as AgentProvider, { sessionId: 'replay-browser' });
    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect(String(fetchSpy.calls.argsFor(1)[0])).toContain('/events/harness-replay?after=4');
    expect(session.messages.map(message => message.content)).toEqual(['original', 'first', 'follow up', 'second']);
    expect(session.messages.map(message => message.role)).toEqual(['USER', 'ASSISTANT', 'USER', 'ASSISTANT']);
    expect(completions.length).toBe(1);
    expect(service.getReconnectBookmark('replay-browser')).toBeNull();
  });

  it('reattaches from a tab bookmark using GET only and preserves system turn attribution', async () => {
    const event = (id: number, name: string, data: unknown) => `id: ${id}\nevent: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const stream = new ReadableStream<Uint8Array>({ start(value) { controller = value; } });
    const fetchSpy = spyOn(window, 'fetch').and.resolveTo(new Response(stream, { status: 200 }));
    const session = service.createSession('reload');
    const run = service.sendMessage(session, 'original', { name: 'coder', displayName: 'Coder' } as AgentProvider, { sessionId: 'reload-browser' });
    const prefix = event(1, 'queued', { processId: 'harness-reload', reconnectable: true })
      + event(2, 'turn_started', { turnId: 1, source: 'initial', text: '' }) + event(3, 'chunk', 'partial');
    controller.enqueue(new TextEncoder().encode(prefix));
    await new Promise(resolve => setTimeout(resolve, 0));
    service.detachStreaming(); controller.close(); await run;
    const checkpoint = service.getReconnectBookmark('reload-browser');
    expect(checkpoint).not.toBeNull();
    fetchSpy.calls.reset();
    fetchSpy.and.resolveTo(new Response(new TextEncoder().encode(prefix
      + event(4, 'turn_complete', { turnId: 1, text: 'first' })
      + event(5, 'turn_started', { turnId: 2, source: 'system', text: 'Process completed' })
      + event(6, 'turn_complete', { turnId: 2, text: 'reviewed' })
      + event(7, 'complete', { content: 'reviewed' })), { status: 200 }));
    let messages: any[] = [];
    service.getLiveMessages().subscribe(value => messages = value);
    await service.resumeRun('reload-browser');
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(String(fetchSpy.calls.mostRecent().args[0])).toContain('/events/harness-reload?after=' + checkpoint!.cursor);
    expect(messages.map(message => message.content)).toEqual(['original', 'first', 'Process completed', 'reviewed']);
    expect(messages[2].role).toBe('SYSTEM');
    expect(service.getReconnectBookmark('reload-browser')).toBeNull();
  });

  describe('a run the page slept through', () => {
    const event = (id: number, name: string, data: unknown) => `id: ${id}\nevent: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
    const body = (text: string) => new Response(new TextEncoder().encode(text), { status: 200 });
    const prefix = event(1, 'queued', { processId: 'harness-asleep', reconnectable: true })
      + event(2, 'turn_started', { turnId: 1, source: 'initial', text: '' }) + event(3, 'chunk', 'partial');

    /** Leaves a reconnect bookmark behind, as a page that went to sleep mid-turn does. */
    async function sleepMidTurn(fetchSpy: jasmine.Spy, browserSessionId: string): Promise<void> {
      let controller!: ReadableStreamDefaultController<Uint8Array>;
      fetchSpy.and.resolveTo(new Response(new ReadableStream<Uint8Array>({ start(value) { controller = value; } }), { status: 200 }));
      const run = service.sendMessage(service.createSession('asleep'), 'original',
        { name: 'coder', displayName: 'Coder' } as AgentProvider, { sessionId: browserSessionId });
      controller.enqueue(new TextEncoder().encode(prefix));
      await new Promise(resolve => setTimeout(resolve, 0));
      service.detachStreaming(); controller.close(); await run;
      expect(service.getReconnectBookmark(browserSessionId)).not.toBeNull();
      fetchSpy.calls.reset();
    }

    it('ends quietly and reloads the transcript when the server no longer holds the run', async () => {
      const fetchSpy = spyOn(window, 'fetch');
      await sleepMidTurn(fetchSpy, 'gone-browser');
      fetchSpy.and.resolveTo(new Response('expired', { status: 410 }));
      const errors: string[] = []; const reloads: string[] = []; const completions: unknown[] = [];
      service.getStreamingError().subscribe(value => errors.push(value));
      service.getTranscriptReloads().subscribe(value => reloads.push(value));
      service.getStreamingComplete().subscribe(value => completions.push(value));
      await service.resumeRun('gone-browser');
      expect(errors).toEqual([]);
      expect(completions.length).toBe(1);
      expect(reloads).toEqual(['gone-browser']);
      expect(service.getReconnectBookmark('gone-browser')).toBeNull();
    });

    it('follows a run past a resync and reloads the transcript when it ends', async () => {
      const fetchSpy = spyOn(window, 'fetch');
      await sleepMidTurn(fetchSpy, 'resync-browser');
      fetchSpy.and.resolveTo(body('event: resync\ndata: {"after":40}\n\n'
        + event(41, 'turn_complete', { turnId: 1, text: 'whole answer' }) + event(42, 'complete', { content: 'whole answer' })));
      const errors: string[] = []; const reloads: string[] = [];
      service.getStreamingError().subscribe(value => errors.push(value));
      service.getTranscriptReloads().subscribe(value => reloads.push(value));
      await service.resumeRun('resync-browser');
      expect(errors).toEqual([]);
      expect(reloads).toEqual(['resync-browser']);
      expect(service.getReconnectBookmark('resync-browser')).toBeNull();
    });

    it('retries a reconnect that fails while the network is still waking', async () => {
      const fetchSpy = spyOn(window, 'fetch');
      await sleepMidTurn(fetchSpy, 'waking-browser');
      // The server replays from the saved cursor, so the replay repeats everything after it.
      const cursor = service.getReconnectBookmark('waking-browser')!.cursor!;
      const replay = [event(3, 'chunk', 'partial'), event(4, 'turn_complete', { turnId: 1, text: 'done' }),
        event(5, 'complete', { content: 'done' })].slice(Math.max(0, cursor - 2)).join('');
      fetchSpy.and.returnValues(Promise.reject(new TypeError('Failed to fetch')),
        Promise.resolve(new Response('proxy', { status: 502 })), Promise.resolve(body(replay)));
      const errors: string[] = [];
      service.getStreamingError().subscribe(value => errors.push(value));
      await service.resumeRun('waking-browser');
      expect(fetchSpy).toHaveBeenCalledTimes(3);
      expect(errors).toEqual([]);
      expect(service.getReconnectBookmark('waking-browser')).toBeNull();
    });
  });

  it('keeps live activity nonterminal and waits for correlated execution acknowledgements', async () => {
    let stream!: ReadableStreamDefaultController<Uint8Array>;
    const body = new ReadableStream<Uint8Array>({ start(controller) { stream = controller; } });
    const emit = (name: string, data: unknown) => stream.enqueue(new TextEncoder().encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`));
    const fetchSpy = spyOn(window, 'fetch').and.resolveTo(new Response(body, { status: 200 }));
    const session = service.createSession('live');
    const run = service.sendMessage(session, 'first', { name: 'coder', displayName: 'Coder' } as AgentProvider);
    emit('start', { processId: 'harness-1' });
    emit('activity', { backgroundable: true, turnActive: true, processes: [], tasks: [] });
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(service.liveControlsReady).toBeTrue();
    fetchSpy.and.resolveTo(new Response(JSON.stringify({ accepted: true }), { status: 202 }));
    let acknowledged = false;
    const reply = service.sendHarnessControl('background').then(value => { acknowledged = true; return value; });
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(acknowledged).toBeFalse();
    const frame = JSON.parse(String((fetchSpy.calls.mostRecent().args[1] as RequestInit).body));
    emit('control', { requestId: 'wrong', action: 'background', ok: true, message: 'wrong' });
    emit('control', { requestId: frame.requestId, action: 'background', ok: true, message: 'Task detached' });
    expect((await reply).message).toBe('Task detached');
    fetchSpy.and.resolveTo(new Response(JSON.stringify({ accepted: true }), { status: 202 }));
    const childReply = service.sendHarnessControl('subagent_input', 'child-1', 'continue child');
    const childFrame = JSON.parse(String((fetchSpy.calls.mostRecent().args[1] as RequestInit).body));
    expect(childFrame.targetId).toBe('child-1');
    expect(childFrame.text).toBe('continue child');
    emit('control', { requestId: childFrame.requestId, action: 'subagent_input', ok: true, message: 'queued' });
    expect((await childReply).ok).toBeTrue();
    expect(service.liveInputHistory).toEqual([]); // child text is not queued into parent input
    emit('chunk', 'first answer');
    emit('turn_complete', { text: 'first answer' });
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(service.getCurrentProcessId()).toBe('harness-1');
    expect(service.liveControlsReady).toBeTrue();
    emit('chunk', 'completion answer');
    emit('turn_complete', { text: 'completion answer' });
    emit('complete', { content: 'completion answer' });
    stream.close();
    await run;
    expect(session.messages[1].content).toBe('first answer\n\ncompletion answer');
    expect(service.liveControlsReady).toBeFalse();
    expect(service.getCurrentProcessId()).toBeNull();
  });

  it('marks unconfirmed process state unknown rather than claiming successful cancellation', () => {
    service.harnessActivity = { backgroundable: true, turnActive: true,
      tasks: [], processes: [{ id: 'p', description: 'build', state: 'RUNNING' }] };
    (service as any).closeHarnessControls();
    expect(service.harnessActivity?.processes[0].state).toBe('UNKNOWN (run ended)');
    expect(service.liveControlsReady).toBeFalse();
  });

  it('rejects controls without a live run and never sends live slash commands as model text', async () => {
    const fetchSpy = spyOn(window, 'fetch');
    await expectAsync(service.sendHarnessControl('background')).toBeRejectedWithError('No live harness run');
    (service as any).currentProcessId = 'harness-test';
    service.liveControlsReady = true;
    await expectAsync(service.sendHarnessControl('input', undefined, '/model x')).toBeRejected();
    await expectAsync(service.sendHarnessControl('subagent_input', 'child', '/model x')).toBeRejected();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('sends stable session and inline attachments to the single JSON harness endpoint', async () => {
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(
          'event: start\ndata: {"processId":"harness-1"}\n\n'
          + 'event: harness_session\ndata: {"session_id":"web-a","provider":"custom","model":"m"}\n\n'
          + 'event: chunk\ndata: "ok"\n\n'
          + 'event: complete\ndata: {"content":"ok","engine":"kompile-cli-main"}\n\n'));
        controller.close();
      }
    });
    const fetchSpy = spyOn(window, 'fetch').and.resolveTo(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' }
    }));
    const session: LocalAgentSession = {
      id: 'agent-storage-id',
      name: 'Browser chat',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      messages: [],
      archived: false,
      totalTokens: 0,
      messageCount: 0
    };
    const agent: AgentProvider = {
      name: 'crawler',
      displayName: 'Crawler',
      command: 'kompile chat',
      skipPermissionsFlag: '--dangerously-skip-permissions',
      skipPermissions: true,
      args: [],
      environment: {},
      available: true,
      isDefault: true,
      description: 'Crawl persona',
      agentType: 'HARNESS',
      supportsVision: true
    };
    const image: MessageAttachment = {
      filename: 'plot.png',
      mimeType: 'image/png',
      base64Data: 'AQID',
      isImage: true,
      previewUrl: 'data:image/png;base64,AQID'
    };

    await service.sendMessage(session, 'crawl this', agent, {
      sessionId: 'browser-visible-session',
      enableMemory: true,
      systemPromptOverride: 'Use the project workflow.',
      enableRag: true,
      attachments: [image]
    });

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.calls.mostRecent().args;
    expect(String(url)).toContain('/agents/chat/stream');
    expect(String(url)).not.toContain('stream-with-files');
    const request = JSON.parse(String((init as RequestInit).body));
    expect(request.sessionId).toBe('browser-visible-session');
    expect(request.agentName).toBe('crawler');
    expect(request.skipPermissions).toBeFalse();
    expect(request.enableMemory).toBeTrue();
    expect(request.systemPromptOverride).toBe('Use the project workflow.');
    expect(request.enableRag).toBeTrue();
    expect(request.attachments[0].base64Data).toBe('AQID');
    expect(request.attachments[0].previewUrl).toBeUndefined();
    expect(session.messages[1].content).toBe('ok');
    expect(session.metadata?.['engine']).toBe('kompile-cli-main');
    expect(storage.updateSession).toHaveBeenCalled();
  });

  it('displays CLI command outcomes without model attribution or replaying commands in history', async () => {
    const response = (events: string) => new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        // Exercise framing across arbitrary network chunks.
        const bytes = new TextEncoder().encode(events);
        controller.enqueue(bytes.slice(0, 37));
        controller.enqueue(bytes.slice(37));
        controller.close();
      }
    }), { status: 200 });
    const event = (name: string, data: unknown) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
    const session = service.createSession('commands');
    const agent = { name: 'coder', displayName: 'Coder' } as AgentProvider;
    const fetchSpy = spyOn(window, 'fetch');
    const errors: string[] = [];
    service.getStreamingError().subscribe(error => errors.push(error));
    for (const status of ['COMPLETED', 'UNKNOWN_COMMAND', 'TERMINAL_REQUIRED', 'LIVE_SESSION_REQUIRED', 'NOT_YET_SUPPORTED']) {
      const outcome = { command: '/example', status, text: `Outcome ${status}`, ok: status === 'COMPLETED', exit: status === 'COMPLETED' ? 0 : 2 };
      fetchSpy.and.resolveTo(response(event('command', outcome)
        + event('complete', { content: outcome.text, commandOutcome: outcome })));
      await service.sendMessage(session, '/example raw args', agent);
      const displayed = session.messages[session.messages.length - 1];
      expect(displayed.role).toBe('SYSTEM');
      expect(displayed.agent).toBeUndefined();
      expect(displayed.content).toBe(outcome.text);
      expect(displayed.streaming).toBeFalse();
    }
    // Structured /model payloads ride along on the outcome verbatim (no Spring-
    // or browser-side reinterpretation) and still stay out of model history.
    const menuOutcome = {
      command: '/model', status: 'INTERACTION_REQUIRED', text: 'Menu text',
      ok: true, exit: 0,
      data: {
        menu: 'model' as const, provider: 'custom', currentModel: 'm-large',
        models: [{ id: 'm-large', contextLimit: 32768, current: true }]
      }
    };
    fetchSpy.and.resolveTo(response(event('command', menuOutcome)
      + event('complete', { content: menuOutcome.text, commandOutcome: menuOutcome })));
    await service.sendMessage(session, '/model', agent);
    const menuDisplayed = session.messages[session.messages.length - 1];
    expect(menuDisplayed.role).toBe('SYSTEM');
    expect(menuDisplayed.commandOutcome?.data).toEqual(menuOutcome.data);
    const restoredMenu = JSON.parse(JSON.stringify(session)) as LocalAgentSession;
    expect(restoredMenu.messages[restoredMenu.messages.length - 1].commandOutcome?.data)
      .toEqual(menuOutcome.data);
    expect(errors).toEqual([]);
    // Markers survive normal JSON session persistence, not just an in-memory set.
    const restored = JSON.parse(JSON.stringify(session)) as LocalAgentSession;
    fetchSpy.and.resolveTo(response(event('complete', { content: 'model answer' })));
    await service.sendMessage(restored, '/custom-skill only raw args', agent);
    let request = JSON.parse(String((fetchSpy.calls.mostRecent().args[1] as RequestInit).body));
    expect(request.message).toBe('/custom-skill only raw args');
    expect(request.chatHistory).toEqual([]);
    fetchSpy.and.resolveTo(response(event('complete', { content: 'next answer' })));
    await service.sendMessage(restored, 'follow up', agent);
    request = JSON.parse(String((fetchSpy.calls.mostRecent().args[1] as RequestInit).body));
    expect(request.chatHistory).toEqual([
      { role: 'USER', content: '/custom-skill only raw args' },
      { role: 'ASSISTANT', content: 'model answer' }
    ]);
  });

  it('reports an abrupt SSE close as an error instead of a successful response', async () => {
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(
          'event: start\ndata: {"processId":"harness-truncated"}\n\n'
          + 'event: chunk\ndata: "partial"\n\n'));
        controller.close();
      }
    });
    spyOn(window, 'fetch').and.resolveTo(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' }
    }));
    const session: LocalAgentSession = {
      id: 'truncated-session',
      name: 'Browser chat',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      messages: [],
      archived: false,
      totalTokens: 0,
      messageCount: 0
    };
    const agent: AgentProvider = {
      name: 'crawler',
      displayName: 'Crawler',
      command: 'kompile chat',
      skipPermissionsFlag: '--dangerously-skip-permissions',
      skipPermissions: false,
      args: [],
      environment: {},
      available: true,
      isDefault: true,
      description: 'Crawl persona',
      agentType: 'HARNESS',
      supportsVision: false
    };
    const errors: string[] = [];
    const completions: unknown[] = [];
    service.getStreamingError().subscribe(error => errors.push(error));
    service.getStreamingComplete().subscribe(message => completions.push(message));

    await service.sendMessage(session, 'crawl this', agent);

    expect(errors).toEqual(['Kompile harness stream ended without a terminal event']);
    expect(completions).toEqual([]);
    expect(session.messages[1].error).toBeTrue();
    expect(session.messages[1].content).toBe('partial');
  });

  /** A harness run whose SSE events the test emits one at a time. */
  const openRun = (session: LocalAgentSession, text: string) => {
    let stream!: ReadableStreamDefaultController<Uint8Array>;
    const body = new ReadableStream<Uint8Array>({ start(controller) { stream = controller; } });
    const fetchSpy = spyOn(window, 'fetch').and.resolveTo(new Response(body, { status: 200 }));
    const run = service.sendMessage(session, text, { name: 'coder', displayName: 'Coder' } as AgentProvider);
    const emit = (name: string, data: unknown) =>
      stream.enqueue(new TextEncoder().encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`));
    const finish = async (content: string) => { emit('complete', { content }); stream.close(); await run; };
    return { fetchSpy, emit, finish };
  };
  const settle = () => new Promise(resolve => setTimeout(resolve, 0));

  it('sends live slash commands as command frames and queues only the ones bound for the model', async () => {
    const { fetchSpy, emit, finish } = openRun(service.createSession('live-commands'), 'first');
    emit('start', { processId: 'harness-commands' });
    emit('activity', { backgroundable: true, turnActive: true, processes: [], tasks: [] });
    await settle();
    await expectAsync(service.sendHarnessControl('command', undefined, 'processes')).toBeRejectedWithError('A command starts with /');
    await expectAsync(service.sendHarnessControl('command', undefined, '  ')).toBeRejectedWithError('Nothing to send');
    const command = (text: string, reply: object) => {
      fetchSpy.and.resolveTo(new Response(JSON.stringify({ accepted: true }), { status: 202 }));
      const answer = service.sendHarnessControl('command', undefined, text);
      const [url, init] = fetchSpy.calls.mostRecent().args;
      const frame = JSON.parse(String((init as RequestInit).body));
      expect(String(url)).toContain('/agents/chat/control/harness-commands');
      expect(frame).toEqual(jasmine.objectContaining({ version: 1, action: 'command', text }));
      emit('control', { requestId: frame.requestId, action: 'command', ...reply });
      return answer;
    };
    expect((await command('/processes', { ok: true, message: 'build  RUNNING' })).message).toBe('build  RUNNING');
    expect((await command('/compact', { ok: false, deferred: true, message: 'Runs after this turn' })).deferred).toBeTrue();
    expect((await command('/review src', { ok: true, queued: true, message: 'Queued' })).queued).toBeTrue();
    // Answered and deferred commands never reach the model; a queued one waits with typed input.
    expect(service.liveInputHistory).toEqual(['/review src']);
    await finish('done');
  });

  it('records harness tool calls where they ran and merges each completion into its call', async () => {
    const session = service.createSession('tools');
    let calls: ToolUseEvent[] = [];
    service.getToolCalls().subscribe(value => calls = value);
    const { emit, finish } = openRun(session, 'read it');
    emit('start', { processId: 'harness-tools' });
    emit('chunk', 'Reading.');
    emit('tool_use', { callId: 'c1', toolName: 'mcp__kompile__read', input: { file_path: 'Foo.java' }, status: 'started' });
    emit('tool_use', { toolName: 'bash', input: 'ls', status: 'started' });
    emit('tool_use', { tool: 'Read', input: 'echoed into the text by a managed lane' });
    emit('chunk', ' Done.');
    emit('tool_result', { toolName: 'bash', ok: false, durationMs: 5, status: 'completed' });
    emit('tool_result', { callId: 'c1', toolName: 'mcp__kompile__read', ok: true, durationMs: 12, status: 'completed',
      detail: { displayName: 'Read', sections: [{ label: 'content', runs: [{ text: 'class Foo {}', file: 'Foo.java' }] }] } });
    emit('tool_result', { toolName: 'grep', ok: true, durationMs: 1 });
    await settle();

    expect(calls.map(call => [call.tool, call.status, call.ok, call.durationMs, call.textOffset])).toEqual([
      ['mcp__kompile__read', 'completed', true, 12, 'Reading.'.length],
      ['bash', 'completed', false, 5, 'Reading.'.length]
    ]);
    expect(calls[0].input).toBe('{"file_path":"Foo.java"}');
    expect(calls[0].detail?.sections?.[0].runs[0].text).toBe('class Foo {}');
    expect(session.messages[1].toolUses).toBe(calls);
    await finish('Reading. Done.');
    expect(session.messages[1].toolUses?.length).toBe(2);
  });

  it('shows streamed reasoning above the answer while the stored answer stays text only', async () => {
    const session = service.createSession('thinking');
    const displayed: string[] = [];
    (service as any).streamingContentRaw$.subscribe(({ content }: { content: string }) => displayed.push(content));
    const { emit, finish } = openRun(session, 'why');
    emit('start', { processId: 'harness-thinking' });
    emit('thinking', 'Checking the ');
    emit('thinking', 'build.');
    await settle();
    expect(displayed[displayed.length - 1]).toBe('<thinking>Checking the build.');
    expect(session.messages[1].content).toBe('');

    emit('chunk', 'It passes.');
    await settle();
    expect(displayed[displayed.length - 1]).toBe('<thinking>Checking the build.</thinking>\n\nIt passes.');
    expect(session.messages[1].content).toBe('It passes.');

    await finish('It passes.');
    expect(session.messages[1].content).toBe('<thinking>Checking the build.</thinking>\n\nIt passes.');
  });

  describe('workflow teams', () => {
    const team = { name: 'review', version: 2, lead: 'lead',
      participants: [
        { id: 'lead', role: 'planner', model: 'custom/lead-model', capabilities: ['plan'], delegatesTo: ['worker'] },
        { id: 'worker', role: 'implementer', model: 'custom/worker-model', capabilities: [] }],
      routing: { implement: 'worker' }, gates: { implementationRequires: 'design', approved: [] as string[] },
      maxConcurrentWorkers: 1 };
    const sse = (name: string, data: unknown) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;

    afterEach(() => {
      for (let index = sessionStorage.length - 1; index >= 0; index--) {
        const key = sessionStorage.key(index);
        if (key?.startsWith('kompile-workflow-team:')) sessionStorage.removeItem(key);
      }
    });

    it('keeps the team a session event reports for the tab and forgets it when a later run has none', async () => {
      const session = service.createSession('team');
      const { fetchSpy, emit, finish } = openRun(session, 'first');
      emit('harness_session', { session_id: 'web-team', provider: 'custom', model: 'lead-model', workflow: team });
      await finish('planned');
      expect(service.getWorkflowTeam(session.id)).toEqual(team);
      (service as any).workflowTeams.clear(); // a reload: only the tab's storage remains
      expect(service.getWorkflowTeam(session.id)).toEqual(team);
      expect(service.getWorkflowTeam('another-session')).toBeNull();

      fetchSpy.and.resolveTo(new Response(new TextEncoder().encode(
        sse('harness_session', { session_id: 'web-team', provider: 'custom', model: 'lead-model' })
        + sse('complete', { content: 'plain' })), { status: 200 }));
      await service.sendMessage(session, 'second', { name: 'coder', displayName: 'Coder' } as AgentProvider);
      expect(service.getWorkflowTeam(session.id)).toBeNull();
      (service as any).workflowTeams.clear();
      expect(service.getWorkflowTeam(session.id)).toBeNull();
    });

    it('approves a gate through the live run and keeps the approvals it reports', async () => {
      const session = service.createSession('live-gate');
      const { fetchSpy, emit, finish } = openRun(session, 'first');
      emit('start', { processId: 'harness-gate' });
      emit('harness_session', { session_id: 'web-gate', workflow: team });
      emit('activity', { backgroundable: false, turnActive: true, processes: [], tasks: [] });
      await settle();
      fetchSpy.and.resolveTo(new Response(JSON.stringify({ accepted: true }), { status: 202 }));
      const approval = service.approveWorkflowGate(session.id, ' design ');
      const [url, init] = fetchSpy.calls.mostRecent().args;
      expect(String(url)).toContain('/agents/chat/control/harness-gate');
      const frame = JSON.parse(String((init as RequestInit).body));
      expect(frame).toEqual(jasmine.objectContaining({ action: 'workflow_approve', text: 'design' }));
      const approved = "Approved gate 'design' for workflow 'review'.";
      emit('control', { requestId: frame.requestId, action: 'workflow_approve', ok: true, message: approved,
        gate: 'design', approved: ['design'] });
      expect(await approval).toEqual({ ok: true, message: approved });
      expect(service.getWorkflowTeam(session.id)?.gates.approved).toEqual(['design']);
      expect(service.liveInputHistory).toEqual([]);

      fetchSpy.and.resolveTo(new Response(JSON.stringify({ accepted: true }), { status: 202 }));
      const next = service.approveWorkflowGate(session.id);
      const nextFrame = JSON.parse(String((fetchSpy.calls.mostRecent().args[1] as RequestInit).body));
      expect('text' in nextFrame).toBeFalse(); // no gate: the one that blocks next
      const done = "Every gate of workflow 'review' is already approved.";
      emit('control', { requestId: nextFrame.requestId, action: 'workflow_approve', ok: false, message: done });
      expect(await next).toEqual({ ok: false, message: done });
      expect(service.getWorkflowTeam(session.id)?.gates.approved).toEqual(['design']);
      await finish('done');
    });

    it('approves a gate between runs through the chat API and shows why one is refused', async () => {
      const session = service.createSession('rest-gate');
      const { fetchSpy, emit, finish } = openRun(session, 'first');
      emit('harness_session', { session_id: 'web-rest', workflow: team });
      await finish('planned');
      const approved = "Approved gate 'design' for workflow 'review'.";
      fetchSpy.and.resolveTo(new Response(JSON.stringify({ ok: true, message: approved, workflow: 'review',
        gate: 'design', approved: ['design'] }), { status: 200 }));
      expect(await service.approveWorkflowGate(session.id, 'design', '/work/project')).toEqual({ ok: true, message: approved });
      const [url, init] = fetchSpy.calls.mostRecent().args;
      expect(String(url)).toContain('/agents/chat/workflow/approve');
      expect(JSON.parse(String((init as RequestInit).body)))
        .toEqual({ sessionId: session.id, workingDirectory: '/work/project', gate: 'design' });
      expect(service.getWorkflowTeam(session.id)?.gates.approved).toEqual(['design']);

      const busy = 'A chat run is in progress; approve the gate from its live controls, or again when it ends.';
      fetchSpy.and.resolveTo(new Response(JSON.stringify({ ok: false, message: busy }), { status: 409 }));
      expect(await service.approveWorkflowGate(session.id)).toEqual({ ok: false, message: busy });
      expect(JSON.parse(String((fetchSpy.calls.mostRecent().args[1] as RequestInit).body))).toEqual({ sessionId: session.id });
      fetchSpy.and.resolveTo(new Response('<html>unavailable</html>', { status: 503 }));
      expect(await service.approveWorkflowGate(session.id)).toEqual({ ok: false, message: 'Gate approval failed (HTTP 503)' });
      expect(service.getWorkflowTeam(session.id)?.gates.approved).toEqual(['design']);
    });

    it('approves a gate of another session through the chat API, never through the live run', async () => {
      const shown = service.createSession('shown');
      const live = service.createSession('live');
      const { fetchSpy, emit, finish } = openRun(live, 'first');
      emit('start', { processId: 'harness-live' });
      emit('harness_session', { session_id: 'web-live', workflow: team });
      emit('activity', { backgroundable: false, turnActive: true, processes: [], tasks: [] });
      await settle();
      fetchSpy.and.resolveTo(new Response(JSON.stringify({ ok: true, message: 'Approved', gate: 'design', approved: ['design'] }),
        { status: 200 }));
      expect(await service.approveWorkflowGate(shown.id, 'design')).toEqual({ ok: true, message: 'Approved' });
      const [url, init] = fetchSpy.calls.mostRecent().args;
      expect(String(url)).toContain('/agents/chat/workflow/approve');
      expect(JSON.parse(String((init as RequestInit).body))).toEqual({ sessionId: shown.id, gate: 'design' });
      expect(service.getWorkflowTeam(live.id)?.gates.approved).toEqual([]);
      await finish('done');
    });

    it('restores only a well-formed team from the tab storage', () => {
      const store = (sessionId: string, value: string) => sessionStorage.setItem('kompile-workflow-team:' + sessionId, value);
      store('kept', JSON.stringify(team));
      store('no-approvals', JSON.stringify({ ...team, gates: { implementationRequires: 'design' } }));
      store('unnamed-participant', JSON.stringify({ ...team, participants: [{ role: 'planner' }] }));
      store('not-json', '{');
      expect(service.getWorkflowTeam('kept')).toEqual(team);
      expect(service.getWorkflowTeam('no-approvals')).toBeNull();
      expect(service.getWorkflowTeam('unnamed-participant')).toBeNull();
      expect(service.getWorkflowTeam('not-json')).toBeNull();
    });
  });

  describe('session insights', () => {
    let http: HttpTestingController;
    const insightsRead = (request: { url: string }) => request.url.endsWith('/agents/chat/session-insights');

    beforeEach(() => http = TestBed.inject(HttpTestingController));
    afterEach(() => http.verify());

    it('reads the session\'s insights for its project, outside the global error snackbar', () => {
      let answer: SessionInsightsSnapshot | undefined;
      service.getSessionInsights('web-1', '/work/project').subscribe(value => answer = value);
      const request = http.expectOne(insightsRead);
      expect(request.request.method).toBe('GET');
      expect(request.request.params.get('sessionId')).toBe('web-1');
      expect(request.request.params.get('workingDirectory')).toBe('/work/project');
      // The drawer polls and shows a failed read itself; a snackbar per poll would repeat it.
      expect(request.request.context.get(SKIP_ERROR_SNACKBAR)).toBeTrue();
      request.flush({ menu: 'insights', available: true, sessionId: 'web-1', lines: ['Judge: no flags'] });
      expect(answer?.lines).toEqual(['Judge: no flags']);
    });

    it('sends only the session and directory it was given', () => {
      service.getSessionInsights().subscribe();
      const request = http.expectOne(insightsRead);
      expect(request.request.params.keys()).toEqual([]);
      request.flush({ menu: 'insights', available: false, status: 'Session insights need the chat session id' });
    });
  });

  describe('insights page', () => {
    let http: HttpTestingController;
    const settings = {
      defaultWindowDays: 7, maxRows: 10, maxExamples: 5, maxSessions: 200, maxBytesPerFile: 4194304,
      maxToolIndexBytes: 268435456, sparklineBuckets: 14, sessionPanel: true
    };
    const view: InsightsSettingsView = { file: '/home/u/.kompile/config/insights.json', settings, defaults: settings };

    beforeEach(() => http = TestBed.inject(HttpTestingController));
    afterEach(() => http.verify());

    it('reads one topic\'s report with its question and project, outside the global error snackbar', () => {
      let answer: InsightsTopicReport | undefined;
      service.getInsightsReport('judge', 'flags for bash', '/work/project').subscribe(value => answer = value);
      const request = http.expectOne(r => r.url.endsWith('/agents/chat/insights'));
      expect(request.request.method).toBe('GET');
      expect(request.request.params.get('topic')).toBe('judge');
      expect(request.request.params.get('question')).toBe('flags for bash');
      expect(request.request.params.get('workingDirectory')).toBe('/work/project');
      expect(request.request.context.get(SKIP_ERROR_SNACKBAR)).toBeTrue();
      request.flush({ menu: 'insights', topic: 'judge', available: true, headline: 'Judge, last 7 days: no verdicts' });
      expect(answer?.headline).toBe('Judge, last 7 days: no verdicts');
    });

    it('asks for the overview by naming no topic', () => {
      service.getInsightsReport().subscribe();
      const request = http.expectOne(r => r.url.endsWith('/agents/chat/insights'));
      expect(request.request.params.keys()).toEqual([]);
      request.flush({ menu: 'insights', topic: 'overview', available: true });
    });

    it('reads and saves insights.json, sending only the changed settings', () => {
      let read: InsightsSettingsView | undefined;
      service.getInsightsSettings().subscribe(value => read = value);
      const get = http.expectOne(r => r.url.endsWith('/agents/chat/insights/config'));
      expect(get.request.method).toBe('GET');
      expect(get.request.context.get(SKIP_ERROR_SNACKBAR)).toBeTrue();
      get.flush(view);
      expect(read?.file).toBe(view.file);

      let saved: InsightsSettingsView | undefined;
      service.saveInsightsSettings({ maxRows: 20 }).subscribe(value => saved = value);
      const put = http.expectOne(r => r.url.endsWith('/agents/chat/insights/config'));
      expect(put.request.method).toBe('PUT');
      expect(put.request.body).toEqual({ maxRows: 20 });
      // The page shows a rejected value next to the form; a snackbar would repeat it.
      expect(put.request.context.get(SKIP_ERROR_SNACKBAR)).toBeTrue();
      put.flush({ ...view, settings: { ...settings, maxRows: 20 } });
      expect(saved?.settings.maxRows).toBe(20);
    });
  });
});
