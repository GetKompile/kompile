import { formatProcessDuration, groupHarnessProcesses, processDetailRows, processTiming,
  harnessAgentIdentity, harnessAgentUsage, harnessAgentDetails, isHarnessAgent } from './harness-processes';

describe('harness process view', () => {
  it('distinguishes measured child usage, aggregate provider totals and live estimates without inventing counts', () => {
    const child = { id: 'child', description: '', state: 'RUNNING', durationMs: 4200, details: {
      kind: 'subagent', source: 'claude-code', model: 'child-model', stream_output_tokens: '18',
      authentication: 'oauth', thinking: 'high', tool_uses: '2', context_tokens: '200', cache_read_tokens: '0' } };
    expect(isHarnessAgent(child)).toBeTrue();
    expect(harnessAgentIdentity(child)).toBe('anthropic / child-model');
    expect(harnessAgentUsage(child)).toBe('↑? in · ↓? out · ~18 streaming out (estimated)');
    expect(harnessAgentUsage({ ...child, details: { total_tokens: '300', stream_output_tokens: '0' } }))
      .toBe('Σ300 total (provider)');
    expect(harnessAgentUsage({ ...child, details: { input_tokens: '0', output_tokens: '42' } }))
      .toBe('↑0 in · ↓42 out');
    expect(harnessAgentDetails(child)).toContain({ label: 'Elapsed', value: '4.2s' });
    expect(harnessAgentDetails(child)).toContain({ label: 'Authentication', value: 'oauth' });
    expect(harnessAgentDetails(child)).toContain({ label: 'Cache read tokens', value: '0' });
    expect(harnessAgentDetails(child)).toContain({ label: 'Last input tokens', value: '200' });
    expect(harnessAgentIdentity({ ...child, details: {} })).toBe('? / ?');
    expect(isHarnessAgent({ ...child, details: {} })).toBeFalse();
  });

  it('formats durations as ProcessManagementTool.formatDuration does', () => {
    expect(formatProcessDuration(850)).toBe('850ms');
    expect(formatProcessDuration(4200)).toBe('4.2s');
    expect(formatProcessDuration(187_000)).toBe('3m7s');
    expect(formatProcessDuration(3_723_000)).toBe('1h2m3s');
    expect(formatProcessDuration(-1)).toBe('');
  });

  it('groups watchers, running commands and recent ones as the /processes panel does', () => {
    const groups = groupHarnessProcesses([
      { id: 'done', description: 'd', state: 'KILLED' },
      { id: 'cmd', description: 'c', state: 'RUNNING', kind: 'command' },
      { id: 'enf', description: 'e', state: 'RUNNING', kind: 'enforcer' },
      { id: 'old-judge', description: 'j', state: 'COMPLETED', kind: 'judge' }
    ]);
    expect(groups.map(group => [group.key, group.entries.map(view => view.id)])).toEqual([
      ['watchers', ['enf']], ['running', ['cmd']], ['recent', ['done', 'old-judge']]
    ]);
    expect(groups[2].entries.map(view => view.icon)).toEqual(['⊘', '✓']);
    expect(groupHarnessProcesses([])).toEqual([]);
  });

  it('shows how long a finished process took and only the detail fields the snapshot carries', () => {
    expect(processTiming({ id: 'p', description: '', state: 'COMPLETED',
      startedAt: '2026-10-10T10:00:00Z', endedAt: '2026-10-10T10:00:00.850Z' })).toBe('took 850ms');
    expect(processTiming({ id: 'p', description: '', state: 'RUNNING' })).toBe('');
    expect(processDetailRows({ id: 'p', description: '', state: 'COMPLETED', exitCode: 0, details: { cwd: '/repo' } }))
      .toEqual([{ label: 'Exit code', value: '0' }, { label: 'cwd', value: '/repo' }]);
  });
});
