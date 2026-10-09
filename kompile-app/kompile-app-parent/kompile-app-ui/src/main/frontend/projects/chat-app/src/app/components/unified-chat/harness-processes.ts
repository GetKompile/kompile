import { HarnessActivityEntry } from '@shared/services/local-agent-chat.service';

/** One process row: the entry plus what the CLI panel derives from it. */
export interface HarnessProcessView {
  id: string;
  process: HarnessActivityEntry;
  icon: string;
  timing: string;
  details: { label: string; value: string }[];
}

/** A section of the process list, as the CLI's /processes panel (StatusBar.renderProcessPanel) lays it out. */
export interface HarnessProcessGroup {
  key: 'watchers' | 'running' | 'recent';
  title: string;
  entries: HarnessProcessView[];
}

/** Watchers, running commands, then finished ones; the harness already limits Recent to the latest few. */
export function groupHarnessProcesses(processes: HarnessActivityEntry[]): HarnessProcessGroup[] {
  const view = (process: HarnessActivityEntry): HarnessProcessView => ({ id: process.id, process,
    icon: processStateIcon(process.state), timing: processTiming(process), details: processDetailRows(process) });
  const running = processes.filter(process => process.state === 'RUNNING');
  const watchers = running.filter(process => process.kind === 'judge' || process.kind === 'enforcer');
  const groups: HarnessProcessGroup[] = [
    { key: 'watchers', title: 'Active watchers', entries: watchers.map(view) },
    { key: 'running', title: 'Running', entries: running.filter(process => !watchers.includes(process)).map(view) },
    { key: 'recent', title: 'Recent', entries: processes.filter(process => process.state !== 'RUNNING').map(view) }
  ];
  return groups.filter(group => group.entries.length > 0);
}

/** The CLI panel's state marks. */
export function processStateIcon(state: string): string {
  switch (state) {
    case 'RUNNING': return '●';
    case 'COMPLETED': return '✓';
    case 'FAILED': return '✗';
    case 'KILLED': return '⊘';
    default: return '?';
  }
}

/** ProcessManagementTool.formatDuration: 850ms, 4.2s, 3m7s, 1h2m3s. */
export function formatProcessDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '';
  const totalSeconds = Math.floor(ms / 1000);
  if (totalSeconds < 60) return ms < 1000 ? `${Math.floor(ms)}ms` : `${(ms / 1000).toFixed(1)}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) return `${minutes}m${seconds}s`;
  return `${Math.floor(minutes / 60)}h${minutes % 60}m${seconds}s`;
}

function clockTime(iso: string | undefined): string {
  const time = iso ? new Date(iso) : null;
  return time && !Number.isNaN(time.getTime()) ? time.toLocaleTimeString() : '';
}

/**
 * When a process ran. A finished one shows how long it took; a running one shows when it started,
 * since a snapshot's duration goes stale between updates.
 */
export function processTiming(process: HarnessActivityEntry): string {
  if (process.state !== 'RUNNING' && process.startedAt && process.endedAt) {
    return `took ${formatProcessDuration(Date.parse(process.endedAt) - Date.parse(process.startedAt))}`;
  }
  const started = clockTime(process.startedAt);
  return started ? `started ${started}` : '';
}

/** The detail lines /process-status prints, for the fields the snapshot carries. */
export function processDetailRows(process: HarnessActivityEntry): { label: string; value: string }[] {
  const rows: { label: string; value: string }[] = [];
  if (process.pid) rows.push({ label: 'PID', value: String(process.pid) });
  const started = clockTime(process.startedAt);
  if (started) rows.push({ label: 'Started', value: started });
  const ended = clockTime(process.endedAt);
  if (ended) rows.push({ label: 'Ended', value: ended });
  if (process.exitCode !== undefined && process.exitCode !== null) rows.push({ label: 'Exit code', value: String(process.exitCode) });
  if (process.logFile) rows.push({ label: 'Log', value: process.logFile });
  for (const [key, value] of Object.entries(process.details || {})) rows.push({ label: key, value });
  return rows;
}
