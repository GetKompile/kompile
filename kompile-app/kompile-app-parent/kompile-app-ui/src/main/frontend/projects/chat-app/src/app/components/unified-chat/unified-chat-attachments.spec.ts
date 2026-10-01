import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { ChatStorageService } from '@shared/services/chat-storage.service';
import { AgentProvider, MessageAttachment } from '@shared/models/api-models';
import { UnifiedChatComponent } from './unified-chat.component';

// Real component send path and real SSE transport, as in unified-chat-commands.spec.ts.
describe('UnifiedChat attachments', () => {
  let component: UnifiedChatComponent;
  let service: LocalAgentChatService;
  let send: jasmine.Spy;
  let fetchSpy: jasmine.Spy;
  let snackBar: jasmine.SpyObj<{ open: (...args: unknown[]) => unknown }>;
  let savedStorage: string | null;
  const agent = { name: 'coder', displayName: 'Coder' } as AgentProvider;
  const event = (name: string, data: unknown) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
  const response = (events: string) => new Response(new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(events));
      controller.close();
    }
  }), { status: 200 });
  const image = (): MessageAttachment => ({
    filename: 'chart.png', mimeType: 'image/png', isImage: true, size: 4,
    base64Data: 'iVBORw==', previewUrl: 'data:image/png;base64,iVBORw=='
  });
  const notes = (): MessageAttachment => ({
    filename: 'notes.txt', mimeType: 'text/plain', isImage: false, size: 5, textContent: 'hello'
  });
  const sentAttachments = () => JSON.parse(String(fetchSpy.calls.mostRecent().args[1].body)).attachments;

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
    snackBar = jasmine.createSpyObj('MatSnackBar', ['open']);
    // snackBar is ctor param 17 (ragService..dialog, then snackBar, router, route).
    component = new UnifiedChatComponent(
      unused, service, unused, unused, unused, unused, unused, unused, unused,
      unused, unused, unused, cdr, unused, unused, unused, snackBar as any, unused, unused
    );
    spyOn<any>(component, 'updateMonitorSubscription');
    spyOn<any>(component, 'refreshContextBudget');
    component.selectedAgent = agent;
    component.agents = [agent];
    component.agentSupportsVision = true;
    component.newChat();
    send = spyOn(service, 'sendMessage').and.callThrough();
    fetchSpy = spyOn(window, 'fetch');
    fetchSpy.and.resolveTo(response(event('complete', { content: 'It is a bar chart.' })));
  });

  afterEach(() => {
    (component as any).cleanupStreaming();
    (component as any).unsubscribeStreamingSubs();
    // Spies outlive afterEach; a throwing setItem would otherwise break the restore.
    if (jasmine.isSpy(Storage.prototype.setItem)) {
      (Storage.prototype.setItem as jasmine.Spy).and.callThrough();
    }
    if (savedStorage === null) localStorage.removeItem('unified_chat_sessions');
    else localStorage.setItem('unified_chat_sessions', savedStorage);
  });

  it('sends attachment payloads but stores only what the transcript shows', async () => {
    component.pendingAttachments = [image(), notes()];
    component.userInput = 'What does this show?';
    component.sendMessage();
    await send.calls.mostRecent().returnValue;

    expect(sentAttachments()).toEqual([
      { filename: 'chart.png', mimeType: 'image/png', base64Data: 'iVBORw==', isImage: true },
      { filename: 'notes.txt', mimeType: 'text/plain', textContent: 'hello', isImage: false }
    ]);
    const stored = JSON.parse(localStorage.getItem('unified_chat_sessions')!);
    const storedUser = stored.find((session: any) => session.id === component.currentSession!.id)
      .messages.find((message: any) => message.role === 'user');
    expect(storedUser.attachments).toEqual([
      { filename: 'chart.png', mimeType: 'image/png', isImage: true, size: 4 },
      { filename: 'notes.txt', mimeType: 'text/plain', isImage: false, size: 5 }
    ]);
    // This page keeps the thumbnail.
    expect(component.messages[0].attachments![0].previewUrl).toBe('data:image/png;base64,iVBORw==');
  });

  it('still sends when the browser refuses to store the sessions', async () => {
    spyOn(Storage.prototype, 'setItem').and.throwError(
      new DOMException('The quota has been exceeded.', 'QuotaExceededError'));
    const warn = spyOn(console, 'warn');
    component.pendingAttachments = [image()];
    component.userInput = 'What does this show?';
    component.sendMessage();
    await send.calls.mostRecent().returnValue;

    expect(warn).toHaveBeenCalledWith('Could not persist chat sessions:', jasmine.any(DOMException));
    expect(sentAttachments()).toEqual([
      { filename: 'chart.png', mimeType: 'image/png', base64Data: 'iVBORw==', isImage: true }
    ]);
    expect(component.isStreaming).toBeFalse();
    expect(component.messages.map(message => message.content))
      .toEqual(['What does this show?', 'It is a bar chart.']);
  });

  it('attaches the CLI loader\'s image types as images, SVG as text, and refuses other images', async () => {
    const bytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47]);
    const svg = '<svg xmlns="http://www.w3.org/2000/svg"/>';
    (component as any).processFiles([
      new File([bytes], 'chart.png', { type: 'image/png' }),
      new File([svg], 'logo.svg', { type: 'image/svg+xml' }),
      new File([bytes], 'scan.bmp', { type: 'image/bmp' }),
      new File([bytes], 'clipboard', { type: 'image/jpeg' }),
      new File([bytes], 'photo.heic', { type: 'image/heic' })
    ]);
    // FileReader is asynchronous: wait for the reads, then long enough for any stray one.
    for (let waited = 0; component.pendingAttachments.length < 3 && waited < 2000; waited += 10) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    await new Promise(resolve => setTimeout(resolve, 50));

    const byName = new Map(component.pendingAttachments.map(attachment => [attachment.filename, attachment]));
    expect([...byName.keys()].sort()).toEqual(['chart.png', 'clipboard.jpg', 'logo.svg']);
    expect(byName.get('chart.png')).toEqual(jasmine.objectContaining({
      isImage: true, mimeType: 'image/png', base64Data: 'iVBORw==',
      previewUrl: 'data:image/png;base64,iVBORw=='
    }));
    // Named for its type, so the CLI sends it as an image rather than an opaque file.
    expect(byName.get('clipboard.jpg')).toEqual(jasmine.objectContaining({
      isImage: true, mimeType: 'image/jpeg', base64Data: 'iVBORw=='
    }));
    expect(byName.get('logo.svg')).toEqual(jasmine.objectContaining({ isImage: false, textContent: svg }));
    expect(byName.get('logo.svg')!.base64Data).toBeUndefined();
    expect(snackBar.open.calls.allArgs()).toEqual([
      ['scan.bmp is not a supported image type (attach PNG, JPEG, GIF or WebP)', 'Dismiss', { duration: 4000 }],
      ['photo.heic is not a supported image type (attach PNG, JPEG, GIF or WebP)', 'Dismiss', { duration: 4000 }]
    ]);
  });
});
