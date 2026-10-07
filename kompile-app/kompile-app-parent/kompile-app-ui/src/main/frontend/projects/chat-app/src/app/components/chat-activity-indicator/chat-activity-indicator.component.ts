import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HarnessActivity, HarnessActivityEntry } from '@shared/services/local-agent-chat.service';

export interface ChatActivity {
  active: boolean;
  label: string;
  tone: 'work' | 'agent';
}
export const IDLE_CHAT_ACTIVITY: ChatActivity = { active: false, label: 'Idle', tone: 'work' };

/** Live pane state only: saved transcript/tool history is not evidence of running work. */
export function chatActivity(state: {
  streaming: boolean;
  loading?: boolean;
  compacting?: boolean;
  transcriptLoading?: boolean;
  harness?: HarnessActivity | null;
  message?: { content: string; isStreaming?: boolean; toolUses?: { tool: string; status?: string }[] };
}): ChatActivity {
  const harness = state.harness;
  const running = (entry: HarnessActivityEntry) =>
    ['RUNNING', 'BACKGROUNDED'].includes(entry.state) && entry.kind !== 'shared' && entry.kind !== 'mcp';
  const processes = (harness?.processes || []).filter(running);
  const children = (harness?.subagents || []).filter(child => child.running && running(child));
  const childIds = new Set(children.map(child => child.id));
  const tasks = (harness?.tasks || []).filter(task => running(task) && !childIds.has(task.id));
  const commands = processes.filter(process => !['judge', 'enforcer'].includes(process.kind || ''));
  const watchers = processes.length - commands.length;
  const counts: string[] = [];
  if (commands.length) counts.push(`${commands.length} process${commands.length === 1 ? '' : 'es'}`);
  if (watchers) counts.push(`${watchers} watcher${watchers === 1 ? '' : 's'}`);
  if (children.length) counts.push(`${children.length} subagent${children.length === 1 ? '' : 's'}`);
  if (tasks.length) counts.push(`${tasks.length} task${tasks.length === 1 ? '' : 's'}`);

  let phase = '';
  if (state.compacting) phase = 'Compacting';
  else if (state.transcriptLoading) phase = 'Loading chat';
  // A backgrounded turn keeps its SSE listener open; that is not foreground work.
  else if (state.streaming && harness?.turnActive !== false) {
    const message = state.message?.isStreaming ? state.message : undefined;
    const tool = message?.toolUses?.filter(call => call.status === 'started').slice(-1)[0];
    if (tool) phase = `Running ${tool.tool}`;
    else if (message?.content && (!message.content.startsWith('<thinking>') || message.content.includes('</thinking>'))) phase = 'Responding';
    else phase = 'Thinking';
  } else if (state.loading && harness?.turnActive !== false) phase = 'Working';

  if (!phase && !counts.length) return IDLE_CHAT_ACTIVITY;
  return { active: true, label: phase ? [phase, ...counts].join(' · ') : `Running ${counts.join(' · ')}`,
    tone: !phase && !commands.length && (children.length > 0 || watchers > 0) ? 'agent' : 'work' };
}

@Component({
  selector: 'app-chat-activity-indicator',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="activity" role="status" [class.active]="activity.active"
    [class.agent]="activity.tone === 'agent'" [attr.aria-label]="activity.label" [title]="activity.label">
    <span *ngIf="activity.active" class="spinner" aria-hidden="true"></span>
    <span class="label">{{ activity.label }}</span>
  </span>`,
  styles: [`
    :host { display: inline-block; max-width: 100%; min-width: 0; vertical-align: middle; }
    .activity { display: inline-flex; align-items: baseline; gap: .35em; max-width: 100%;
      font-size: .8rem; line-height: 1.4; color: var(--text-secondary, #888); }
    .active { color: #b77900; }
    .agent { color: #a855f7; }
    .label { white-space: normal; overflow-wrap: anywhere; min-width: 0; }
    .spinner { flex: none; font-family: monospace; }
    /* The same eight braille frames as AnsiConstants.SPINNER_FRAMES in the CLI. */
    .spinner::before { content: '⣋'; animation: cli-spinner .8s step-end infinite; }
    @keyframes cli-spinner {
      0%, 100% { content: '⣋'; } 12.5% { content: '⣙'; } 25% { content: '⣹'; }
      37.5% { content: '⣸'; } 50% { content: '⣼'; } 62.5% { content: '⣴'; }
      75% { content: '⣦'; } 87.5% { content: '⣧'; }
    }
    @media (prefers-reduced-motion: reduce) { .spinner::before { animation: none; } }
  `]
})
export class ChatActivityIndicatorComponent {
  @Input() activity: ChatActivity = IDLE_CHAT_ACTIVITY;
}
