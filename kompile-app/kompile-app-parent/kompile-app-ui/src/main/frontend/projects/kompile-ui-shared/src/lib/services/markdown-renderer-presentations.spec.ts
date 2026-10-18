import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';
import { ToolPresentation, ToolUseEvent } from '../models/api-models';
import { MarkdownRendererService, ToolPresentationContext, validateToolPresentation } from './markdown-renderer.service';

// Deliberately generic: the shared renderer knows no tactical component or domain schema.
describe('MarkdownRendererService host-owned tool presentations', () => {
  const context: ToolPresentationContext = { sessionId: 'chat-1', registrations: [
    { kind: 'example.record', serverId: 'example', toolName: 'show_record', validateData: data => Object.keys(data).length === 1 && data['id'] === 7 }
  ] };
  const presentation = (): ToolPresentation => ({ version: 1, kind: 'example.record', data: { id: 7 },
    source: { serverId: 'example', toolName: 'show_record' },
    identity: { sessionId: 'chat-1', turnId: '0123456789abcdef0123456789abcdef', callId: 'call-1' } });
  const call = (): ToolUseEvent => ({ tool: 'mcp__example__show_record', input: '', callId: 'call-1',
    status: 'completed', ok: true, detail: { displayName: 'Show record', presentation: presentation() } });
  let renderer: MarkdownRendererService;
  beforeEach(() => { TestBed.configureTestingModule({}); renderer = TestBed.inject(MarkdownRendererService); });

  it('carries only an immutable registered successful envelope, independently of chart/Markdown', () => {
    const event = call();
    const view = renderer.renderToolCall(event, false, context);
    expect(view.presentation).toEqual(presentation());
    expect(view.presentation).not.toBe(event.detail!.presentation);
    expect(Object.isFrozen(view.presentation!.data)).toBeTrue();
    expect(renderer.renderToolCall(event, false).presentation).toBeUndefined();
    const html = TestBed.inject(DomSanitizer).sanitize(1, renderer.renderMarkdownSafe('**Ordinary** `code`'));
    expect(html).toContain('<strong>Ordinary</strong>');
    // The renderer emits its own inline-code class; assert the class, not bare <code>.
    expect(html).toContain('inline-code">code</code>');
  });

  it('rejects malformed/unknown/source/identity/error envelopes without interpreting prose', () => {
    const cases: ((event: any) => void)[] = [
      e => e.status = 'started', e => e.ok = false, e => delete e.ok,
      e => e.detail.error = 'denied', e => delete e.detail.presentation,
      e => e.detail.presentation.kind = 'unregistered', e => e.detail.presentation.version = 2,
      e => e.detail.presentation.url = 'https://rogue.invalid',
      e => e.detail.presentation.source.serverId = 'other', e => e.detail.presentation.source.toolName = 'delete_record',
      e => e.detail.presentation.identity.sessionId = 'other-chat', e => e.detail.presentation.identity.callId = 'other-call',
      e => e.detail.presentation.identity.turnId = 'not-a-turn', e => e.detail.presentation.identity.turnId = { toString: 'bad' },
      e => e.detail.presentation.identity = null,
      e => e.detail.presentation.source = null, e => e.detail.presentation.data = [],
      e => e.detail.presentation.data = { id: 7, url: 'javascript:alert(1)' },
      e => e.tool = 'write_record', e => { e.toolName = 'show_record'; e.tool = 'write_record'; }
    ];
    for (const change of cases) {
      const event = call(); change(event);
      expect(validateToolPresentation(event, context)).withContext(change.toString()).toBeUndefined();
    }
    const text = JSON.stringify(presentation());
    expect(renderer.parseMessageSegments(text, false).every(segment => !segment.toolCall?.presentation)).toBeTrue();
    const html = TestBed.inject(DomSanitizer).sanitize(1,
      renderer.renderMarkdownSafe('<app-product-inline-board-card></app-product-inline-board-card><iframe src="https://rogue.invalid"></iframe>'));
    expect(html).not.toContain('iframe');
    expect(html).not.toContain('app-product-inline-board-card');
  });
});
