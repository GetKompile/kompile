import { formatProcessDuration, groupHarnessProcesses, processDetailRows, processTiming } from './harness-processes';

describe('harness process view', () => {
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
