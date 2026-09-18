import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';

import { LocalAgentChatService } from './local-agent-chat.service';
import { ChatStorageService } from './chat-storage.service';
import { AgentProvider, LocalAgentSession, MessageAttachment } from '../models/api-models';

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
});
