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
          <thead><tr><th>Full session ID</th><th>Calls</th><th>Argument tokens</th><th>Fully measured payload tokens</th><th>Unavailable / partial</th></tr></thead>
          <tbody><tr *ngFor="let row of usage.perSession">
            <td><button type="button" class="identifier" data-testid="usage-session" (click)="sessionSelected.emit(row.sessionId)">{{ row.sessionId }}</button></td>
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
          <p>Session: {{ call.sessionId }} · requested tool: {{ call.requestedToolName || 'Unavailable' }} ·
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
