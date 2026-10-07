import { TestBed } from '@angular/core/testing';
import { HarnessActivity } from '@shared/services/local-agent-chat.service';
import { ChatActivityIndicatorComponent, chatActivity } from './chat-activity-indicator.component';

const harness = (changes: Partial<HarnessActivity> = {}): HarnessActivity => ({
  backgroundable: false, turnActive: false, processes: [], tasks: [], subagents: [], ...changes
});

describe('CLI-style chat activity indicator', () => {
  it('follows foreground thinking, response, tools and compaction phases', () => {
    expect(chatActivity({ streaming: true }).label).toBe('Thinking');
    expect(chatActivity({ streaming: true, message: { content: '<thinking>Plan', isStreaming: true } }).label).toBe('Thinking');
    expect(chatActivity({ streaming: true, message: { content: '<thinking>Plan</thinking>Reply', isStreaming: true } }).label).toBe('Responding');
    expect(chatActivity({ streaming: true, message: { content: 'Reply', isStreaming: true,
      toolUses: [{ tool: 'bash', status: 'started' }] } }).label).toBe('Running bash');
    expect(chatActivity({ streaming: true, compacting: true }).label).toBe('Compacting');
    expect(chatActivity({ streaming: false, transcriptLoading: true }).label).toBe('Loading chat');
    expect(chatActivity({ streaming: false, loading: true }).label).toBe('Working');
  });

  it('ignores tool history rather than treating an old invocation as live', () => {
    const message = { content: 'Old response', toolUses: [{ tool: 'bash', status: 'started' }] };
    expect(chatActivity({ streaming: false, message }).active).toBeFalse();
    expect(chatActivity({ streaming: true, message }).label).toBe('Thinking');
  });

  it('shows per-chat processes and subagents even after the foreground is backgrounded', () => {
    const activity = harness({ processes: [{ id: 'p', description: 'Build', state: 'RUNNING', kind: 'command' }],
      subagents: [{ id: 'a', description: 'Review', state: 'BACKGROUNDED', type: 'reviewer', running: true, canSend: true, canCancel: true }],
      tasks: [{ id: 'a', description: 'Review', state: 'RUNNING' }, { id: 't', description: 'Test', state: 'RUNNING' }] });
    expect(chatActivity({ streaming: true, harness: activity }).label).toBe('Running 1 process · 1 subagent · 1 task');
    activity.turnActive = true;
    expect(chatActivity({ streaming: true, harness: activity }).label).toBe('Thinking · 1 process · 1 subagent · 1 task');
    activity.processes = [];
    expect(chatActivity({ streaming: false, harness: activity }).tone).toBe('agent');
  });

  it('excludes shared foreign work, MCP infrastructure and ended/unknown work', () => {
    const activity = harness({ processes: [
      { id: 'foreign', description: 'Another chat', state: 'RUNNING', kind: 'shared', owner: 'other' },
      { id: 'mcp', description: 'MCP', state: 'RUNNING', kind: 'mcp' },
      { id: 'done', description: 'Done', state: 'COMPLETED' },
      { id: 'failed', description: 'Failed', state: 'FAILED' },
      { id: 'unknown', description: 'Lost connection', state: 'UNKNOWN (run ended)' }
    ] });
    expect(chatActivity({ streaming: true, harness: activity }).label).toBe('Idle');
    activity.processes.push({ id: 'judge', description: 'Judge', state: 'RUNNING', kind: 'judge' });
    expect(chatActivity({ streaming: false, harness: activity })).toEqual({ active: true, label: 'Running 1 watcher', tone: 'agent' });
  });

  it('removes the animated spinner as soon as work ends and exposes the phase accessibly', async () => {
    await TestBed.configureTestingModule({ imports: [ChatActivityIndicatorComponent] }).compileComponents();
    const fixture = TestBed.createComponent(ChatActivityIndicatorComponent);
    fixture.componentRef.setInput('activity', chatActivity({ streaming: true }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]').getAttribute('aria-label')).toBe('Thinking');
    expect(fixture.nativeElement.querySelector('.spinner')).not.toBeNull();
    expect(getComputedStyle(fixture.nativeElement.querySelector('.spinner'), '::before').animationName).toContain('cli-spinner');
    fixture.componentRef.setInput('activity', chatActivity({ streaming: false }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.spinner')).toBeNull();
    expect(fixture.nativeElement.textContent.trim()).toBe('Idle');
    fixture.destroy();
  });
});
