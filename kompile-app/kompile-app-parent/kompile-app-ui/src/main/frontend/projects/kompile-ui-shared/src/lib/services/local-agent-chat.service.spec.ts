import { TestBed } from '@angular/core/testing';
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
