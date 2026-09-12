import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';
import { MarkdownRendererService } from '@shared/services/markdown-renderer.service';
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
      renderer, unused, unused, unused, unused, unused, unused, unused
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
});
