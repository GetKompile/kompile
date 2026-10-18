import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { LocalAgentChatService } from './local-agent-chat.service';
import { ChatStorageService } from './chat-storage.service';
import { AgentProvider, createUserMessage, MessageAttachment } from '../models/api-models';

type SendOptions = NonNullable<Parameters<LocalAgentChatService['sendMessage']>[3]>;

const sessionId = 'product-transport-session';
const agent = { name: 'role:product-guide', displayName: 'Managed guide', agentType: 'CLI' } as AgentProvider;
const event = (id: number, name: string, data: unknown) => `id: ${id}\nevent: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
const completed = () => new Response(event(1, 'complete', { content: 'Done' }));

describe('LocalAgentChatService managed product transport', () => {
  let service: LocalAgentChatService;
  let storage: jasmine.SpyObj<ChatStorageService>;

  beforeEach(() => {
    storage = jasmine.createSpyObj('ChatStorageService', ['updateSession', 'updateTab']);
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [LocalAgentChatService, { provide: ChatStorageService, useValue: storage }]
    });
    service = TestBed.inject(LocalAgentChatService);
  });

  afterEach(() => {
    for (const store of [localStorage, sessionStorage]) {
      for (const id of [sessionId, 'ordinary-transport-session']) {
        store.removeItem('kompile-live-run:' + id);
        store.removeItem('kompile-finished-run:' + id);
      }
    }
  });

  it('sends exactly product identity/message keys and publishes the matching bookmark before SSE', async () => {
    let accept!: (response: Response) => void;
    const session = service.createSession('Managed guide', agent);
    session.messages.push(createUserMessage(session.id, 'Browser-only history'));
    const history = spyOn<any>(service, 'buildChatHistory').and.callThrough();
    const fetchSpy = spyOn(window, 'fetch').and.callFake(() => new Promise<Response>(resolve => accept = resolve));
    const pending = service.sendMessage(session, 'Help with this product', agent, {
      managedProduct: true, sessionId, workingDirectory: '/managed/product', messageStartIndex: 7
    });
    const request = JSON.parse(String(fetchSpy.calls.first().args[1]?.body));
    expect(Object.keys(request).sort()).toEqual(['agentName', 'message', 'sessionId', 'turnId', 'workingDirectory']);
    expect(request).toEqual({ message: 'Help with this product', sessionId, turnId: jasmine.stringMatching(/^[0-9a-f]{32}$/),
      agentName: 'role:product-guide', workingDirectory: '/managed/product' });
    expect(fetchSpy.calls.first().args[0]).toBe(service.backendUrl + '/agents/chat/stream');
    expect(fetchSpy.calls.first().args[1]?.method).toBe('POST');
    expect(history).not.toHaveBeenCalled();
    const bookmark = service.getReconnectBookmark(sessionId)!;
    expect(bookmark.runId).toBe('harness-product-' + request.turnId);
    expect(service.getCurrentProcessId()).toBe(bookmark.runId);
    expect(bookmark.browserSessionId).toBe(sessionId);
    expect(bookmark.agent.name).toBe('role:product-guide');
    expect(bookmark.messageStartIndex).toBe(7);
    expect(bookmark.cursor).toBe(0);
    expect(session.messages.slice(-2).map(message => message.role)).toEqual(['USER', 'ASSISTANT']);
    accept(completed());
    await pending;
    expect(service.getReconnectBookmark(sessionId)).toBeNull();
  });

  it('keeps the derived product run identity through the server queued acknowledgement', async () => {
    let stream!: ReadableStreamDefaultController<Uint8Array>;
    const fetchSpy = spyOn(window, 'fetch').and.resolveTo(new Response(new ReadableStream<Uint8Array>({
      start(value) { stream = value; }
    })));
    const pending = service.sendMessage(service.createSession('Product'), 'Continue', agent, { managedProduct: true, sessionId });
    const request = JSON.parse(String(fetchSpy.calls.first().args[1]?.body));
    const runId = 'harness-product-' + request.turnId;
    stream.enqueue(new TextEncoder().encode(event(1, 'queued', { processId: runId, reconnectable: true })
      + event(2, 'start', { processId: runId })));
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(service.getReconnectBookmark(sessionId)?.runId).toBe(runId);
    expect(service.getCurrentProcessId()).toBe(runId);
    expect(Object.keys(request).sort()).toEqual(['agentName', 'message', 'sessionId', 'turnId']);
    stream.enqueue(new TextEncoder().encode(event(3, 'complete', { content: 'Done' })));
    stream.close();
    await pending;
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it('stops the canonical product run before acknowledgement using only the existing cancel API', async () => {
    let accept!: (response: Response) => void;
    const fetchSpy = spyOn(window, 'fetch').and.callFake((_url, init) => init?.body
      ? new Promise<Response>(resolve => accept = resolve) : Promise.resolve(new Response('{}')));
    const pending = service.sendMessage(service.createSession('Product'), 'Work', agent, { managedProduct: true, sessionId });
    const request = JSON.parse(String(fetchSpy.calls.first().args[1]?.body));
    const runId = 'harness-product-' + request.turnId;
    expect(service.getReconnectBookmark(sessionId)?.runId).toBe(runId);
    service.cancelStreaming();
    expect(fetchSpy.calls.allArgs().map(args => String(args[0]))).toEqual([
      service.backendUrl + '/agents/chat/stream', service.backendUrl + '/agents/chat/cancel/' + runId
    ]);
    expect(fetchSpy.calls.mostRecent().args[1]?.method).toBe('POST');
    expect(fetchSpy.calls.mostRecent().args[1]?.body).toBeUndefined();
    expect(service.getReconnectBookmark(sessionId)).toBeNull();
    expect(fetchSpy.calls.first().args[1]?.signal?.aborted).toBeTrue();
    accept(completed());
    await pending;
  });

  it('generates a fresh opaque turn identity for each explicitly managed submission', async () => {
    const fetchSpy = spyOn(window, 'fetch').and.callFake(() => Promise.resolve(completed()));
    const session = service.createSession('Product');
    await service.sendMessage(session, 'First', agent, { managedProduct: true, sessionId });
    await service.sendMessage(session, 'Second', agent, { managedProduct: true, sessionId });
    const requests = fetchSpy.calls.allArgs().map(args => JSON.parse(String(args[1]?.body)));
    expect(requests.every(request => /^[0-9a-f]{32}$/.test(request.turnId))).toBeTrue();
    expect(requests[0].turnId).not.toBe(requests[1].turnId);
  });

  it('leaves the full ordinary JSON payload, history, attachment projection and run identity unchanged', async () => {
    const fetchSpy = spyOn(window, 'fetch').and.callFake(() => Promise.resolve(completed()));
    const session = service.createSession('Ordinary');
    session.messages.push(createUserMessage(session.id, 'Ordinary browser history'));
    await service.sendMessage(session, 'Ordinary message', agent, {
      managedProduct: false, sessionId: 'ordinary-transport-session', workingDirectory: '/ordinary', skipPermissions: true,
      systemPromptOverride: 'Ordinary override', enableRag: true, messageStartIndex: 4,
      attachments: [{ name: 'note.txt', mimeType: 'text/plain', content: 'note', previewUrl: 'UI-only' } as MessageAttachment]
    });
    const request = JSON.parse(String(fetchSpy.calls.first().args[1]?.body));
    expect(request).toEqual({
      message: 'Ordinary message', runId: jasmine.stringMatching(/^harness-[0-9a-f]{32}$/), messageStartIndex: 4,
      sessionId: 'ordinary-transport-session', agentName: 'role:product-guide', skipPermissions: true,
      workingDirectory: '/ordinary', enableMemory: true, systemPromptOverride: 'Ordinary override', includeHistory: true,
      chatHistory: [{ role: 'USER', content: 'Ordinary browser history' }],
      enableRag: true, ragMaxResults: 5, ragSimilarityThreshold: 0, includeKeywordSearch: true, includeSemanticSearch: true,
      enableGraphRag: false, graphRagMaxResults: 5, graphRagSearchType: 'LOCAL', timeoutSeconds: 300,
      attachments: [{ filename: 'note.txt', mimeType: 'text/plain', textContent: 'note', isImage: false }]
    });
    expect(request.turnId).toBeUndefined();
    expect(request.managedProduct).toBeUndefined();
  });

  const unsafe: { name: string; options: SendOptions; error: RegExp }[] = [
    { name: 'attachments', options: { attachments: [{ name: 'note.txt' } as MessageAttachment] }, error: /attachments/ },
    { name: 'permission bypass', options: { skipPermissions: true }, error: /permissions/ },
    { name: 'system prompt override', options: { systemPromptOverride: 'Override' }, error: /system prompt/ },
    { name: 'empty system prompt override', options: { systemPromptOverride: '' }, error: /system prompt/ },
    { name: 'browser history import', options: { includeHistory: true }, error: /browser history/ },
    { name: 'browser history limit override', options: { maxHistoryMessages: 5 }, error: /browser history/ },
    { name: 'RAG override', options: { enableRag: true }, error: /retrieval/ },
    { name: 'graph RAG override', options: { enableGraphRag: true }, error: /retrieval/ },
    { name: 'folder context override', options: { folderId: 'unrelated' }, error: /folder context/ }
  ];
  for (const scenario of unsafe) {
    it('rejects managed ' + scenario.name + ' before fetch or transcript/bookmark mutation', async () => {
      const fetchSpy = spyOn(window, 'fetch');
      const session = service.createSession('Product');
      storage.updateSession.calls.reset();
      await expectAsync(service.sendMessage(session, 'Work', agent, {
        managedProduct: true, sessionId, ...scenario.options
      })).toBeRejectedWithError(scenario.error);
      expect(fetchSpy).not.toHaveBeenCalled();
      expect(storage.updateSession).not.toHaveBeenCalled();
      expect(session.messages).toEqual([]);
      expect(service.getReconnectBookmark(sessionId)).toBeNull();
      expect(service.isCurrentlyStreaming()).toBeFalse();
    });
  }
});
