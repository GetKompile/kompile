import { Component, OnDestroy, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Subject, takeUntil } from 'rxjs';

interface AgentBundle {
  id: string;
  name: string;
  engine: string;
  digest: string;
  importedAt: string;
  filename: string;
  entries: string[];
  manifest: Record<string, unknown>;
}

interface AgentRun {
  runId: string;
  bundleId: string;
  prompt: string;
  state: string;
  startedAt: string;
  finishedAt?: string;
  exitCode?: number;
  error?: string;
  lastSequence: number;
}

interface AgentRunEvent {
  sequence: number;
  runId: string;
  timestamp: string;
  type: string;
  payload: Record<string, unknown>;
}

@Component({
  selector: 'app-agent-bundles',
  standalone: false,
  templateUrl: './agent-bundles.component.html',
  styleUrls: ['./agent-bundles.component.css']
})
export class AgentBundlesComponent implements OnInit, OnDestroy {
  private readonly destroy$ = new Subject<void>();
  private eventSource: EventSource | null = null;

  bundles: AgentBundle[] = [];
  runs: AgentRun[] = [];
  events: AgentRunEvent[] = [];
  selectedBundle: AgentBundle | null = null;
  selectedRun: AgentRun | null = null;
  selectedFile: File | null = null;
  mcpTools: string[] = [];
  prompt = '';
  timeoutSeconds = 0;
  loading = false;
  uploading = false;
  discoveringTools = false;
  error: string | null = null;

  private readonly eventTypes = [
    'RUN_STARTED', 'RUN_RUNNING', 'OUTPUT', 'TEXT_DELTA', 'TOOL_COMPLETED',
    'MCP_TOOL_COMPLETED', 'MCP_DISCOVERY', 'SESSION_ATTACHED', 'RESULT',
    'AGENT_ERROR', 'LOG', 'RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED', 'RUN_ORPHANED'
  ];

  constructor(private readonly http: HttpClient) {}

  ngOnInit(): void {
    this.loadBundles();
    this.loadRuns();
  }

  ngOnDestroy(): void {
    this.closeStream();
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadBundles(): void {
    this.loading = true;
    this.http.get<AgentBundle[]>('/api/agent-bundles')
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: bundles => {
          this.bundles = bundles;
          if (!this.selectedBundle && bundles.length) this.selectedBundle = bundles[0];
          this.loading = false;
        },
        error: err => this.fail(err)
      });
  }

  loadRuns(): void {
    this.http.get<AgentRun[]>('/api/agent-bundles/runs')
      .pipe(takeUntil(this.destroy$))
      .subscribe({ next: runs => this.runs = runs, error: err => this.fail(err) });
  }

  fileChanged(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile = input.files && input.files.length ? input.files[0] : null;
  }

  importBundle(): void {
    if (!this.selectedFile) return;
    this.uploading = true;
    this.error = null;
    const body = new FormData();
    body.append('bundle', this.selectedFile, this.selectedFile.name);
    this.http.post<AgentBundle>('/api/agent-bundles/import', body)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: bundle => {
          this.bundles = [bundle, ...this.bundles];
          this.selectedBundle = bundle;
          this.selectedFile = null;
          this.uploading = false;
        },
        error: err => { this.uploading = false; this.fail(err); }
      });
  }

  startRun(): void {
    if (!this.selectedBundle || !this.prompt.trim()) return;
    this.error = null;
    this.http.post<AgentRun>(`/api/agent-bundles/${this.selectedBundle.id}/runs`, {
      prompt: this.prompt.trim(), timeoutSeconds: this.timeoutSeconds || 0
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: run => {
        this.runs = [run, ...this.runs.filter(existing => existing.runId !== run.runId)];
        this.prompt = '';
        this.selectRun(run);
      },
      error: err => this.fail(err)
    });
  }

  discoverTools(): void {
    if (!this.selectedBundle) return;
    this.discoveringTools = true;
    this.error = null;
    this.http.get<string[]>(`/api/agent-bundles/${this.selectedBundle.id}/tools`)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: tools => { this.mcpTools = tools; this.discoveringTools = false; },
        error: err => { this.discoveringTools = false; this.fail(err); }
      });
  }

  selectRun(run: AgentRun): void {
    this.selectedRun = run;
    this.events = [];
    this.closeStream();
    this.http.get<AgentRunEvent[]>(`/api/agent-bundles/runs/${run.runId}/events`)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: events => {
          this.events = events;
          this.openStream(run.runId, events.length ? events[events.length - 1].sequence : 0);
        },
        error: err => this.fail(err)
      });
  }

  cancelRun(run: AgentRun): void {
    this.http.post<AgentRun>(`/api/agent-bundles/runs/${run.runId}/cancel`, {})
      .pipe(takeUntil(this.destroy$))
      .subscribe({ next: updated => this.updateRun(updated), error: err => this.fail(err) });
  }

  isActive(run: AgentRun): boolean {
    return run.state === 'STARTING' || run.state === 'RUNNING';
  }

  eventText(event: AgentRunEvent): string {
    const payload = event.payload || {};
    if (typeof payload['text'] === 'string' && payload['text']) return payload['text'] as string;
    if (typeof payload['message'] === 'string' && payload['message']) return payload['message'] as string;
    if (typeof payload['error'] === 'string' && payload['error']) return payload['error'] as string;
    return JSON.stringify(payload);
  }

  private openStream(runId: string, after: number): void {
    this.eventSource = new EventSource(
      `/api/agent-bundles/runs/${encodeURIComponent(runId)}/events/stream?after=${after}`);
    const handler = (message: Event) => {
      const event = message as MessageEvent<string>;
      try {
        const parsed = JSON.parse(event.data) as AgentRunEvent;
        if (!this.events.some(existing => existing.sequence === parsed.sequence)) this.events.push(parsed);
      } catch {
        // Ignore a malformed event; the JSON replay endpoint remains authoritative.
      }
      this.loadRuns();
    };
    this.eventTypes.forEach(type => this.eventSource?.addEventListener(type, handler));
    this.eventSource.onerror = () => {
      if (this.selectedRun && !this.isActive(this.selectedRun)) this.closeStream();
    };
  }

  private closeStream(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }

  private updateRun(run: AgentRun): void {
    this.runs = [run, ...this.runs.filter(existing => existing.runId !== run.runId)];
    if (this.selectedRun?.runId === run.runId) this.selectedRun = run;
  }

  private fail(error: any): void {
    this.loading = false;
    this.uploading = false;
    this.error = error?.error?.error || error?.error?.message || error?.message || 'Agent request failed';
  }
}
