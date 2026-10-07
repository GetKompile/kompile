import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ToolTokenMeasurement, ToolUsageReport, ToolUsageTotals } from '@shared/services/local-agent-chat.service';

/** Presentation only: filters and pagination always reread the server's complete filtered totals. */
@Component({
  selector: 'app-tool-usage-details',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="tool-usage" data-testid="tool-usage-details" aria-label="Tool token breakdown">
      <section *ngIf="usage.selectedSession as session" data-testid="session-metrics" class="provenance">
        <h3>{{ session.title || '(untitled)' }}</h3><p>Session UUID: <code>{{ session.sessionId }}</code></p>
        <ng-container *ngIf="session.sessionMetrics as metrics; else noMetrics">
          <p>Provider input: {{ count(metrics.tokens?.input) }} · output: {{ count(metrics.tokens?.output) }} · total: {{ count(metrics.tokens?.total) }}</p>
          <p>Cache read: {{ count(metrics.tokens?.cacheRead) }} · cache creation: {{ count(metrics.tokens?.cacheCreation) }}</p>
          <p>Estimated tokens (not provider usage): {{ count(metrics.tokens?.estimatedTotal) }}</p>
          <p>Compactions: {{ count(metrics.agentic?.compactions) }} · tokens before: {{ count(metrics.agentic?.compactionTokensBefore) }} ·
            after: {{ count(metrics.agentic?.compactionTokensAfter) }} · saved: {{ count(metrics.agentic?.tokensSavedByCompaction) }}</p>
          <details><summary>All recorded session metrics</summary><pre>{{ metrics | json }}</pre></details>
        </ng-container>
        <ng-template #noMetrics><p>{{ session.metricsStatus || 'Session metrics unavailable' }}</p></ng-template>
      </section>
      <section *ngIf="usage.catalog as catalog" data-testid="catalog-calls">
        <h3>Recorded tool calls — session catalog</h3>
        <p>These historical records are separate from measured token totals. Select a call to read its full recorded input.</p>
        <p *ngIf="catalog.status">{{ catalog.status }}</p>
        <p *ngIf="catalog.truncated">Older catalog records are outside the scan limit.</p>
        <div class="table-scroll"><table aria-label="Session catalog calls">
          <thead><tr><th>Call ID</th><th>Tool</th><th>Source / agent</th><th>Duration</th><th>Error</th></tr></thead>
          <tbody><tr *ngFor="let call of catalog.calls">
            <td><button type="button" class="identifier" (click)="callSelected.emit(call.id)">{{ call.id }}</button></td>
            <td>{{ call.toolName }}<p>{{ call.summary }}</p></td><td>{{ call.source }} / {{ call.agentName }}</td>
            <td>{{ count(call.durationMs) }} ms</td><td>{{ call.isError == null ? 'Unavailable' : call.isError }}</td>
          </tr></tbody>
        </table></div>
        <button type="button" *ngIf="usage.offset > 0" (click)="pageSelected.emit(previousOffset)">Previous catalog calls</button>
        <button type="button" *ngIf="catalog.hasMore" (click)="pageSelected.emit(usage.offset + usage.limit)">Next catalog calls</button>
        <ng-container *ngFor="let call of catalog.calls">
          <section *ngIf="selectedCall === call.id" class="provenance">
            <h4>{{ call.toolName }} — {{ call.id }}</h4><p>Session UUID: {{ call.sessionId }} · started: {{ call.timestamp | date:'medium' }}</p>
            <h4>Original input</h4><pre>{{ call.toolInput || 'Input was not recorded' }}</pre>
            <details><summary>All recorded call data</summary><pre>{{ call | json }}</pre></details>
            <p>Token measurements are available only in the separate measured ledger below.</p>
            <p *ngIf="!call.detail?.available">{{ call.detail?.status || 'Output was not recorded for this historical call.' }}</p>
            <details *ngIf="call.detail?.context"><summary>Correlation, source and agent</summary><pre>{{ call.detail?.context | json }}</pre></details>
            <ng-container *ngFor="let field of contentFields">
              <section *ngIf="call.detail?.[field] as page">
                <h4>{{ field }}</h4><p *ngIf="!page.available">{{ page.status || 'Not recorded' }}</p>
                <ng-container *ngIf="page.available">
                  <pre>{{ page.text }}</pre><p>Character offset {{ page.offset }} · file size {{ page.sizeBytes }} bytes</p>
                  <button type="button" *ngIf="page.offset" (click)="contentPageRequested.emit({sessionId: call.sessionId, invocationId: call.id, field: field, offset: 0})">First page</button>
                  <button type="button" *ngIf="page.hasMore" (click)="contentPageRequested.emit({sessionId: call.sessionId, invocationId: call.id, field: field, offset: page.nextOffset || 0})">Read next page</button>
                </ng-container>
              </section>
            </ng-container>
          </section>
        </ng-container>
      </section>
      <h3>Locally measured tool tokens</h3>
      <p>Arguments and returned payload are local tokenizer measurements, not provider input/output tokens.
        Payload totals include fully measured (MEASURED) calls only. Partial and unavailable measurements are not zero.</p>
      <p class="totals" data-testid="usage-totals">
        {{ usage.summary.calls }} filtered calls · measured arguments: {{ count(usage.summary.argumentsTokens) }} ·
        fully measured payload: {{ payloadTotal(usage.summary) }}
      </p>
      <p data-testid="usage-coverage">
        {{ usage.summary.unmeasuredPayloadCalls }} payloads unavailable ·
        {{ usage.summary.partialPayloadCalls }} partial · {{ count(usage.summary.degradedCalls) }} degraded calls.
        Totals cover all filtered records before pagination, within the scanned index.
      </p>
      <p *ngIf="usage.truncated" class="badge" role="status">Truncated index: older calls may be missing.</p>
      <p *ngIf="usage.malformedLines" class="badge">{{ usage.malformedLines }} malformed index lines skipped.</p>
      <details *ngIf="usage.measurementBuckets">
        <summary>Measurement coverage by status</summary><pre>{{ usage.measurementBuckets | json }}</pre>
      </details>
      <div class="table-scroll">
        <table aria-label="Tokens by tool">
          <thead><tr><th>Tool</th><th>Calls</th><th>Argument tokens</th><th>Fully measured payload tokens</th><th>Unavailable / partial</th></tr></thead>
          <tbody><tr *ngFor="let row of usage.perTool">
            <td><button type="button" class="identifier" data-testid="usage-tool" (click)="toolSelected.emit(row.tool)">{{ row.tool }}</button></td>
            <td>{{ row.calls }}</td><td>{{ count(row.argumentsTokens) }}</td><td>{{ payloadTotal(row) }}</td>
            <td>{{ row.unmeasuredPayloadCalls }} / {{ row.partialPayloadCalls }}</td>
          </tr></tbody>
        </table>
        <table aria-label="Tokens by session">
          <thead><tr><th>Title / full session UUID</th><th>Provider tokens / compactions</th><th>Calls</th><th>Argument tokens</th><th>Fully measured payload tokens</th><th>Unavailable / partial</th></tr></thead>
          <tbody><tr *ngFor="let row of usage.perSession">
            <td><strong>{{ row.title || '(untitled)' }}</strong><br><button type="button" class="identifier" data-testid="usage-session" (click)="sessionSelected.emit(row.sessionId)">{{ row.sessionId }}</button></td>
            <td>{{ count(row.sessionMetrics?.tokens?.total) }} / {{ count(row.sessionMetrics?.agentic?.compactions) }}</td>
            <td>{{ row.calls }}</td><td>{{ count(row.argumentsTokens) }}</td><td>{{ payloadTotal(row) }}</td>
            <td>{{ row.unmeasuredPayloadCalls }} / {{ row.partialPayloadCalls }}</td>
          </tr></tbody>
        </table>
      </div>
      <h3>Calls · full invocation IDs</h3>
      <p data-testid="usage-page">{{ usage.calls.length ? usage.offset + 1 : 0 }}–{{ usage.offset + usage.calls.length }} of {{ usage.totalCalls }} filtered calls</p>
      <div class="table-scroll">
        <table aria-label="Tool calls">
          <thead><tr><th>Invocation / session / tool</th><th>Started</th><th>Duration</th><th>Outcome / delivery</th><th>Argument tokens</th><th>Returned payload tokens</th></tr></thead>
          <tbody><tr *ngFor="let call of usage.calls">
            <td>
              <button type="button" class="identifier" data-testid="usage-call" (click)="callSelected.emit(call.invocationId)">{{ call.invocationId }}</button><br>
              <strong>{{ call.title || '(untitled)' }}</strong><br>
              <button type="button" class="identifier" (click)="sessionSelected.emit(call.sessionId)">{{ call.sessionId }}</button><br>
              <button type="button" (click)="toolSelected.emit(call.tool)">{{ call.tool }}</button>
            </td>
            <td>{{ call.startedEpochMs == null ? 'Unavailable' : (call.startedEpochMs | date:'medium') }}</td>
            <td>{{ count(call.durationMs) }} ms</td>
            <td>{{ call.outcome || 'Unavailable' }} / {{ call.disposition || 'Unavailable' }}
              <span *ngIf="call.errorResponse" class="badge">Error response</span>
              <span *ngIf="call.accountingDegraded" class="badge">Accounting degraded</span>
            </td>
            <td>{{ measurement(call.arguments) }}</td><td>{{ measurement(call.payload) }}</td>
          </tr></tbody>
        </table>
      </div>
      <p *ngIf="!usage.calls.length">No calls match this filter.</p>
      <nav class="pagination" aria-label="Call pages">
        <button type="button" data-testid="usage-previous" [disabled]="usage.offset <= 0 || usage.limit <= 0"
          (click)="pageSelected.emit(previousOffset)">Previous</button>
        <button type="button" data-testid="usage-next" [disabled]="!usage.hasMore || usage.limit <= 0"
          (click)="pageSelected.emit(usage.offset + usage.limit)">Next</button>
      </nav>
      <ng-container *ngFor="let call of usage.calls">
        <section *ngIf="selectedCall === call.invocationId" class="provenance" data-testid="call-provenance">
          <h3>Invocation {{ call.invocationId }}</h3>
          <p>{{ call.title || '(untitled)' }} · Session UUID: {{ call.sessionId }} · requested tool: {{ call.requestedToolName || 'Unavailable' }} ·
            resolved tool: {{ call.resolvedToolName || 'Unavailable' }}</p>
          <p>Outcome: {{ call.outcome || 'Unavailable' }} · delivery: {{ call.disposition || 'Unavailable' }} ·
            duration: {{ count(call.durationMs) }} ms · error response: {{ call.errorResponse == null ? 'Unavailable' : call.errorResponse }} ·
            accounting degraded: {{ call.accountingDegraded == null ? 'Unavailable' : call.accountingDegraded }}</p>
          <p>Started: {{ call.startedEpochMs == null ? 'Unavailable' : (call.startedEpochMs | date:'medium') }} ·
            finished: {{ call.finishedEpochMs == null ? 'Unavailable' : (call.finishedEpochMs | date:'medium') }}</p>
          <p *ngIf="call.accountingNote">Accounting note: {{ call.accountingNote }}</p>
          <div *ngFor="let item of [{label: 'Arguments', value: call.arguments}, {label: 'Returned payload', value: call.payload}, {label: 'Raw payload', value: call.rawPayload}]">
            <h4>{{ item.label }} measurement: {{ measurement(item.value) }}</h4>
            <dl>
              <dt>Status</dt><dd>{{ item.value?.status || 'Unavailable' }}</dd>
              <dt>Representation</dt><dd>{{ item.value?.representation || 'Unavailable' }}</dd>
              <dt>Method</dt><dd>{{ item.value?.method || 'Unavailable' }}</dd>
              <dt>Detail</dt><dd>{{ item.value?.detail || 'Unavailable' }}</dd>
              <dt>Tokenizer ID / version</dt><dd>{{ item.value?.tokenizerId || 'Unavailable' }} / {{ item.value?.tokenizerVersion || 'Unavailable' }}</dd>
            </dl>
          </div>
          <section data-testid="call-content">
            <h4>Recorded invocation data</h4>
            <p *ngIf="!call.detail?.available">{{ call.detail?.status || 'Content was not recorded for this historical call.' }}</p>
            <details *ngIf="call.detail?.context"><summary>Correlation, source and agent</summary><pre>{{ call.detail?.context | json }}</pre></details>
            <details *ngIf="call.detail?.catalog"><summary>Full catalog record / original input</summary><pre>{{ call.detail?.catalog | json }}</pre></details>
            <ng-container *ngFor="let field of contentFields">
              <section *ngIf="call.detail?.[field] as page">
                <h4>{{ field }}</h4><p *ngIf="!page.available">{{ page.status || 'Not recorded' }}</p>
                <ng-container *ngIf="page.available">
                  <pre>{{ page.text }}</pre><p>Character offset {{ page.offset }} · file size {{ page.sizeBytes }} bytes</p>
                  <button type="button" *ngIf="page.offset" (click)="contentPageRequested.emit({sessionId: call.sessionId, invocationId: call.invocationId, field: field, offset: 0})">First page</button>
                  <button type="button" *ngIf="page.hasMore" (click)="contentPageRequested.emit({sessionId: call.sessionId, invocationId: call.invocationId, field: field, offset: page.nextOffset || 0})">Read next page</button>
                </ng-container>
              </section>
            </ng-container>
          </section>
          <h4>Model execution events — separate provider ledger</h4>
          <pre>{{ call.modelExecutions == null ? 'Unavailable' : (call.modelExecutions | json) }}</pre>
        </section>
      </ng-container>
      <details>
        <summary>Provider/model execution tokens — separate ledger (not added to tool totals)</summary>
        <pre>{{ usage.modelExecutions == null ? 'Unavailable' : (usage.modelExecutions | json) }}</pre>
      </details>
    </section>
  `,
  styles: [`
    .tool-usage { margin: 16px 0; } .table-scroll { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; margin: 12px 0; }
    th, td { text-align: left; vertical-align: top; padding: 8px; border-bottom: 1px solid var(--border-color, #ccc); }
    .identifier { overflow-wrap: anywhere; white-space: normal; text-align: left; }
    button { cursor: pointer; color: var(--color-primary, #1976d2); background: var(--bg-body, transparent); border: 1px solid var(--border-color, #ccc); border-radius: 4px; padding: 4px 8px; }
    button:disabled { opacity: .5; cursor: default; } .pagination { display: flex; gap: 8px; }
    .badge { color: var(--status-warning-text, #a56500); } .provenance { overflow-wrap: anywhere; border: 1px solid var(--border-color, #ccc); padding: 12px; margin: 12px 0; }
    dl { display: grid; grid-template-columns: minmax(100px, 180px) 1fr; } dd { margin-left: 8px; }
    pre { overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; } summary { cursor: pointer; }
  `]
})
export class ToolUsageDetailsComponent {
  @Input({ required: true }) usage!: ToolUsageReport;
  @Input() selectedCall = '';
  @Output() toolSelected = new EventEmitter<string>();
  @Output() sessionSelected = new EventEmitter<string>();
  @Output() callSelected = new EventEmitter<string>();
  @Output() pageSelected = new EventEmitter<number>();
  readonly contentFields = ['arguments', 'output', 'rawOutput', 'structured'] as const;
  @Output() contentPageRequested = new EventEmitter<{ sessionId: string; invocationId: string;
    field: 'arguments' | 'output' | 'rawOutput' | 'structured'; offset: number }>();

  get previousOffset(): number { return Math.max(0, this.usage.offset - this.usage.limit); }

  count(value: number | null | undefined): string {
    return value != null && Number.isFinite(value) ? String(value) : 'Unavailable';
  }

  payloadTotal(row: ToolUsageTotals): string {
    return row.calls > row.unmeasuredPayloadCalls + row.partialPayloadCalls
      ? this.count(row.payloadTokens) : 'Unavailable (no fully measured payloads)';
  }

  measurement(value?: ToolTokenMeasurement): string {
    if (!value) return 'Unavailable';
    if (value.status === 'MEASURED' || value.status === 'FULLY_MEASURED') return this.count(value.tokens);
    if (value.status === 'PARTIALLY_MEASURED' || value.status === 'PARTIAL') {
      return `${this.count(value.tokens)} (partial; excluded from payload totals)`;
    }
    return `Unavailable (${value.status || 'unknown'})`;
  }
}
