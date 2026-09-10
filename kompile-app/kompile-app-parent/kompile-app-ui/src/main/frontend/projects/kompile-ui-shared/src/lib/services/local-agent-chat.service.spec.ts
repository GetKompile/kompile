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
