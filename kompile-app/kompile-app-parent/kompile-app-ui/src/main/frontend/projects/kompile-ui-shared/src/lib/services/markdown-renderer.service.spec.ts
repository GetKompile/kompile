import { TestBed } from '@angular/core/testing';
import { SafeHtml } from '@angular/platform-browser';

import { MarkdownRendererService } from './markdown-renderer.service';
import { ToolCallDetail, ToolCallRun, ToolUseEvent } from '../models/api-models';

describe('MarkdownRendererService harness tool calls', () => {
  let renderer: MarkdownRendererService;
  const html = (safe: SafeHtml) => (safe as any).changingThisBreaksApplicationSecurity as string;
  const completed = (detail: Partial<ToolCallDetail>, extra: Partial<ToolUseEvent> = {}): ToolUseEvent => ({
    tool: 'mcp__kompile__read', toolName: 'mcp__kompile__read', input: '{}', callId: 'call-1',
    status: 'completed', ok: true, detail: { displayName: 'Read', ...detail }, ...extra
  });
  const runsHtml = (runs: ToolCallRun[]) =>
    html(renderer.renderToolCall(completed({ sections: [{ label: 'content', runs }] }), false).sections[0].html);

  beforeEach(() => {
    TestBed.configureTestingModule({});
    renderer = TestBed.inject(MarkdownRendererService);
  });

  it('draws a completed call as the CLI row with its detail highlighted', () => {
    const view = renderer.renderToolCall(completed({
      action: 'read', title: 'src/main/Foo.java', metadata: '1 line', preview: 'public class Foo',
      sections: [{ label: 'content', note: '… (tool detail truncated at 2400 chars / 48 lines)',
        runs: [{ text: 'public class Foo { int x = 42; }', file: 'src/main/Foo.java' }] }]
    }), false);

    expect(view.name).toBe('Read');
    expect(view.state).toBe('ok');
    expect([view.action, view.title, view.metadata, view.preview, view.error])
      .toEqual(['read', 'src/main/Foo.java', '1 line', 'public class Foo', undefined]);
    expect(view.sections.map(section => [section.label, section.diff, section.note]))
      .toEqual([['content', false, '… (tool detail truncated at 2400 chars / 48 lines)']]);
    expect(html(view.sections[0].html)).toContain('<span class="hljs-keyword">public</span>');
    expect(html(view.sections[0].html)).toContain('<span class="hljs-number">42</span>');
  });

  it('resolves a run language as the CLI does: fence tag, file name, extension, then family', () => {
    expect(runsHtml([{ text: '{"a": 1}', file: 'JSON' }])).toContain('hljs-attr');
    expect(runsHtml([{ text: 'project(demo)', file: 'native/CMakeLists.txt' }])).toContain('hljs-keyword');
    expect(runsHtml([{ text: 'int main() { return 0; }', file: 'src/kernel.cu' }]))
      .toContain('<span class="hljs-keyword">return</span>');
    expect(runsHtml([{ text: 'echo "hi" # note', file: 'run.zzz', family: 'hash' }])).toContain('hljs-comment');
    // Plain text is no language, so the family still applies.
    expect(runsHtml([{ text: 'def f():\n    return None', file: 'notes.txt', family: 'python' }]))
      .toContain('<span class="hljs-keyword">def</span>');
  });

  it('shows a run with no language plain and escaped', () => {
    expect(runsHtml([{ text: '<b>bold</b> & more' }])).toBe('&lt;b&gt;bold&lt;/b&gt; &amp; more');
    expect(runsHtml([{ text: 'x = 1', file: 'notes.txt' }])).toBe('x = 1');
    expect(runsHtml([{ text: 'x = 1', file: 'data.zzz' }])).toBe('x = 1');
  });

  it('highlights each run whole so a block comment spanning lines stays one token', () => {
    const joined = runsHtml([
      { text: '/* first\n   second */', file: 'Foo.java' },
      { text: 'echo ok', family: 'hash' }
    ]);

    expect(joined).toContain('<span class="hljs-comment">/* first\n   second */</span>\n');
    expect(joined).toContain('<span class="hljs-built_in">echo</span> ok');
  });

  it('never lets a run inject markup', () => {
    const markup = runsHtml([{ text: '<script>alert(1)</script><img src=x onerror=alert(1)>', family: 'markup' }]);

    expect(markup).toContain('hljs-name');
    expect(markup).not.toContain('<script');
    expect(markup).not.toContain('<img');
  });

  it('colors a diff by line prefix without highlighting it', () => {
    const view = renderer.renderToolCall(completed({ sections: [{ label: 'diff', diff: true, runs: [
      { text: '--- a/Foo.java\n+++ b/Foo.java\n@@ -1 +1 @@', file: 'Foo.java' },
      { text: '-int a = 1;\n+int a = 2;\n keep <x>', file: 'Foo.java' }
    ] }] }), false);

    expect(view.sections[0].diff).toBeTrue();
    expect(html(view.sections[0].html)).toBe([
      '<span class="diff-meta">--- a/Foo.java</span>',
      '<span class="diff-meta">+++ b/Foo.java</span>',
      '<span class="diff-meta">@@ -1 +1 @@</span>',
      '<span class="diff-del">-int a = 1;</span>',
      '<span class="diff-add">+int a = 2;</span>',
      '<span class="diff-ctx"> keep &lt;x&gt;</span>'
    ].join('\n'));
  });

  it('states a call from its lifecycle and names it from the tool when there is no row', () => {
    const started: ToolUseEvent = {
      tool: 'mcp__kompile__code_search', toolName: 'mcp__kompile__code_search', input: '{}', callId: 'c', status: 'started'
    };

    expect(renderer.renderToolCall(started, true))
      .toEqual(jasmine.objectContaining({ name: 'Code Search', state: 'running', sections: [] }));
    expect(renderer.renderToolCall(started, false).state).toBe('stopped');
    expect(renderer.renderToolCall({ ...started, status: 'completed' }, false).state).toBe('ok');
    expect(renderer.renderToolCall({ tool: 'exec', input: '', status: 'started' }, true).name).toBe('Exec');
    const failed = renderer.renderToolCall(completed({ displayName: 'Bash', error: 'exit 2' }, { ok: false }), false);
    expect([failed.name, failed.state, failed.error]).toEqual(['Bash', 'failed', 'exit 2']);
  });

  it('prettifies tool names as the CLI does', () => {
    expect(['mcp__kompile__read', 'mcp__kompile__code_search', 'ToolSearch', 'exec', '']
      .map(name => MarkdownRendererService.prettifyToolName(name)))
      .toEqual(['Read', 'Code Search', 'ToolSearch', 'Exec', 'unknown']);
  });
});
