import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';
import { ToolUseEvent } from '@shared/models/api-models';
import { MarkdownRendererService, MessageSegment } from '@shared/services/markdown-renderer.service';
import { UnifiedChatComponent } from './unified-chat.component';

describe('UnifiedChat streaming render cache', () => {
  let component: UnifiedChatComponent;
  let renderer: MarkdownRendererService;
  const message = (content: string, isStreaming = true, id = 'message') => ({
    id, content, isStreaming, role: 'assistant' as const, timestamp: new Date()
  });

  beforeEach(() => {
    TestBed.configureTestingModule({});
    renderer = new MarkdownRendererService(TestBed.inject(DomSanitizer));
    // Exercise the real cache field initializers without starting network/polling lifecycle hooks.
    const unused = null as any;
    component = new UnifiedChatComponent(
      unused, unused, unused, unused, unused, unused, unused, unused, unused, unused,
      renderer, unused, unused, unused, unused, unused, unused, unused, unused
    );
  });

  it('reuses live segments and rendered markdown on repeated change detection', () => {
    const parse = spyOn(renderer, 'parseMessageSegments').and.callThrough();
    const render = spyOn(renderer, 'renderMarkdownSafe').and.callThrough();
    const live = message('Hello');
    const segments = component.getMessageSegments(live);
    expect(component.getMessageSegments({ ...live })).toBe(segments);
    expect(parse).toHaveBeenCalledTimes(1);
    expect(render).toHaveBeenCalledTimes(1);
    const html = component.getRenderedMarkdown(live);
    expect(component.getRenderedMarkdown({ ...live, isStreaming: false })).toBe(html);
    expect(render).toHaveBeenCalledTimes(2);
  });

  it('invalidates equal-length replacements in both caches, live and completed', () => {
    for (const isStreaming of [true, false]) {
      const before = message('Hello', isStreaming);
      const after = message('World', isStreaming);
      const oldSegments = component.getMessageSegments(before);
      const oldHtml = component.getRenderedMarkdown(before);
      expect(component.getMessageSegments(after)[0].content).toBe('World');
      expect(component.getMessageSegments(after)[0].renderedContent).not.toBe(oldSegments[0].renderedContent);
      expect(component.getRenderedMarkdown(after)).not.toBe(oldHtml);
    }
  });

  it('reparses on completion but preserves unchanged SafeHtml', () => {
    const parse = spyOn(renderer, 'parseMessageSegments').and.callThrough();
    const live = message('Hello');
    const before = component.getMessageSegments(live);
    const after = component.getMessageSegments({ ...live, isStreaming: false });
    expect(parse).toHaveBeenCalledTimes(2);
    expect(parse).toHaveBeenCalledWith('Hello', false);
    expect(after[0].renderedContent).toBe(before[0].renderedContent);
    expect(component.trackBySegment(0, after[0])).toBe(component.trackBySegment(0, before[0]));
  });

  it('does not retain streaming-only parsing after an unfinished thinking block completes', () => {
    const live = message('<thinking>Working');
    expect(component.getMessageSegments(live)[0].type).toBe('thinking');
    const completed = component.getMessageSegments({ ...live, isStreaming: false });
    expect(completed[0].type).toBe('text');
    expect(completed[0].isStreaming).not.toBeTrue();
  });

  it('only renders changed segments when more content arrives', () => {
    const render = spyOn(renderer, 'renderMarkdownSafe').and.callThrough();
    const prefix = 'Intro\n\n<thinking>Done</thinking>\n\n';
    const before = component.getMessageSegments(message(prefix + 'A'));
    render.calls.reset();
    const after = component.getMessageSegments(message(prefix + 'AB'));
    expect(render).toHaveBeenCalledTimes(1);
    expect(after[0].renderedContent).toBe(before[0].renderedContent);
    expect(after[1].renderedContent).toBe(before[1].renderedContent);
    expect(component.trackBySegment(2, after[2])).toBe(component.trackBySegment(2, before[2]));
  });

  it('caches empty content and bounds both caches to latest entries for 500 messages', () => {
    const empty = component.getMessageSegments(message(''));
    expect(component.getMessageSegments(message(''))).toBe(empty);
    for (let i = 0; i < 510; i++) {
      component.getMessageSegments(message(String(i), true, String(i)));
      component.getRenderedMarkdown(message(String(i), true, String(i)));
    }
    for (let i = 0; i < 20; i++) {
      component.getMessageSegments(message('Latest ' + i, true, '509'));
      component.getRenderedMarkdown(message('Latest ' + i, true, '509'));
    }
    expect((component as any).segmentCache.size).toBe(500);
    expect((component as any).renderedMarkdownCache.size).toBe(500);
    expect((component as any).segmentCache.has('0')).toBeFalse();
    expect((component as any).segmentCache.get('509').content).toBe('Latest 19');
  });

  describe('harness tool calls', () => {
    const call = (fields: Partial<ToolUseEvent> = {}): ToolUseEvent => ({
      tool: 'bash', input: '{"command":"make"}', callId: 'c1', status: 'completed', ok: true,
      detail: { displayName: 'Bash', title: 'make', sections: [{ label: 'output', runs: [{ text: 'echo ok', family: 'hash' }] }] },
      ...fields
    });
    const types = (segments: MessageSegment[]) => segments.map(segment => segment.type);

    it('places each card where its call ran, below the reasoning, ties in run order', () => {
      const segments = component.getMessageSegments({
        ...message('<thinking>Plan</thinking>\n\nBefore after', false),
        toolUses: [call({ textOffset: 7 }), call({ callId: 'c2', textOffset: 7, detail: { displayName: 'Read' } })]
      });
      expect(types(segments)).toEqual(['thinking', 'text', 'tool_call', 'tool_call', 'text']);
      expect(segments.map(segment => segment.toolCall?.name ?? segment.content)).toEqual(['Plan', 'Before', 'Bash', 'Read', 'after']);
      const section = segments[2].toolCall!.sections[0];
      expect(section.label).toBe('output');
      expect((section.html as any).changingThisBreaksApplicationSecurity).toContain('<span class="hljs-built_in">echo</span> ok');
    });

    it('clamps offsets to the answer and puts a call without one at the end', () => {
      const segments = component.getMessageSegments({ ...message('Answer', false), toolUses: [
        call({ callId: 'late', textOffset: 999, detail: { displayName: 'Late' } }),
        call({ callId: 'none', detail: { displayName: 'None' } }),
        call({ callId: 'early', textOffset: -3, detail: { displayName: 'Early' } })
      ] });
      expect(segments.map(segment => segment.toolCall?.name ?? segment.content)).toEqual(['Early', 'Answer', 'Late', 'None']);
    });

    it('keeps the plain path for tool events without a status', () => {
      const echoed = { ...message('Answer', false), toolUses: [{ tool: 'bash', input: '{}' }] };
      expect(types(component.getMessageSegments(echoed))).toEqual(['text']);
    });

    it('shows an open call running while the run streams and stopped once it ends', () => {
      const live = { ...message('Working'), toolUses: [call({ status: 'started', ok: undefined, detail: undefined, textOffset: 0 })] };
      expect(component.getMessageSegments(live)[0].toolCall).toEqual(jasmine.objectContaining({ name: 'Bash', state: 'running' }));
      expect(component.getMessageSegments({ ...live, isStreaming: false })[0].toolCall!.state).toBe('stopped');
    });

    it('renders a finished call once while the answer keeps streaming', () => {
      const render = spyOn(renderer, 'renderToolCall').and.callThrough();
      const done = call({ textOffset: 0 });
      const first = component.getMessageSegments({ ...message('A'), toolUses: [done] });
      const next = component.getMessageSegments({ ...message('AB'), toolUses: [done] });
      expect(render).toHaveBeenCalledTimes(1);
      expect(next[0].toolCall).toBe(first[0].toolCall);
      expect(next[1].content).toBe('AB');
    });
  });
});
