import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ToolUsageReport } from '@shared/services/local-agent-chat.service';
import { ToolUsageDetailsComponent } from './tool-usage-details.component';

const usage = (): ToolUsageReport => ({
  summary: { calls: 3, argumentsTokens: 0, payloadTokens: 0, unmeasuredPayloadCalls: 1, partialPayloadCalls: 1, degradedCalls: 1 },
  perTool: [{ tool: 'read', calls: 3, argumentsTokens: 0, payloadTokens: 0, unmeasuredPayloadCalls: 1, partialPayloadCalls: 1 }],
  perSession: [{ sessionId: 'full-session-id-not-abbreviated', calls: 3, argumentsTokens: 0, payloadTokens: 0, unmeasuredPayloadCalls: 1, partialPayloadCalls: 1 }],
  calls: [
    { invocationId: 'measured-zero', sessionId: 'full-session-id-not-abbreviated', tool: 'read',
      arguments: { tokens: 0, status: 'MEASURED' }, payload: { tokens: 0, status: 'MEASURED' } },
    { invocationId: 'unknown', sessionId: 'full-session-id-not-abbreviated', tool: 'read',
      payload: { tokens: 0, status: 'UNAVAILABLE' } },
    { invocationId: 'partial', sessionId: 'full-session-id-not-abbreviated', tool: 'read',
      payload: { tokens: 19, status: 'PARTIALLY_MEASURED' } }
  ],
  offset: 0, limit: 3, totalCalls: 3, hasMore: false, truncated: true, malformedLines: 2,
  modelExecutions: { inputTokens: 900, outputTokens: 100 }, measurementBuckets: { FULLY_MEASURED: 1 }
});

describe('ToolUsageDetailsComponent', () => {
  let fixture: ComponentFixture<ToolUsageDetailsComponent>;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ToolUsageDetailsComponent] });
    fixture = TestBed.createComponent(ToolUsageDetailsComponent);
    fixture.componentRef.setInput('usage', usage());
    fixture.detectChanges();
  });

  it('shows measured zero distinctly from unavailable and partial measurements', () => {
    const rows: NodeListOf<HTMLTableRowElement> = fixture.nativeElement.querySelectorAll('table[aria-label="Tool calls"] tbody tr');
    expect(rows[0].cells[5].textContent?.trim()).toBe('0');
    expect(rows[1].cells[5].textContent).toContain('Unavailable (UNAVAILABLE)');
    expect(rows[2].cells[5].textContent).toContain('19 (partial; excluded from payload totals)');
    expect(fixture.componentInstance.measurement({ status: 'MEASURED', tokens: null })).toBe('Unavailable');
    expect(fixture.componentInstance.measurement({ status: 'FULLY_MEASURED', tokens: 0 })).toBe('0');
    expect(fixture.componentInstance.measurement(undefined)).toBe('Unavailable');
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('fully measured payload: 0');
    expect(text).toContain('Truncated index');
    expect(text).toContain('2 malformed index lines');
    expect(text).toContain('separate ledger (not added to tool totals)');
    expect(fixture.componentInstance.payloadTotal({ calls: 2, payloadTokens: 0, unmeasuredPayloadCalls: 1, partialPayloadCalls: 1 })).toContain('Unavailable');
  });

  it('emits full tool/session/call IDs and page offsets without altering totals', () => {
    const component = fixture.componentInstance;
    spyOn(component.toolSelected, 'emit');
    spyOn(component.sessionSelected, 'emit');
    spyOn(component.callSelected, 'emit');
    spyOn(component.pageSelected, 'emit');
    fixture.nativeElement.querySelector('[data-testid="usage-tool"]').click();
    fixture.nativeElement.querySelector('[data-testid="usage-session"]').click();
    fixture.nativeElement.querySelector('[data-testid="usage-call"]').click();
    expect(component.toolSelected.emit).toHaveBeenCalledWith('read');
    expect(component.sessionSelected.emit).toHaveBeenCalledWith('full-session-id-not-abbreviated');
    expect(component.callSelected.emit).toHaveBeenCalledWith('measured-zero');
    fixture.componentRef.setInput('usage', { ...usage(), offset: 3, hasMore: true, totalCalls: 20 });
    fixture.detectChanges();
    fixture.nativeElement.querySelector('[data-testid="usage-next"]').click();
    fixture.nativeElement.querySelector('[data-testid="usage-previous"]').click();
    expect(component.pageSelected.emit).toHaveBeenCalledWith(6);
    expect(component.pageSelected.emit).toHaveBeenCalledWith(0);
    expect(component.usage.summary.calls).toBe(3);
  });

  it('shows title and full UUID, compactions, provider usage and readable paged content', () => {
    const report = usage();
    report.selectedSession = { sessionId: 'full-session-id-not-abbreviated', title: 'Resume conversation title',
      sessionMetrics: { tokens: { input: 123, output: 45, total: 168 }, agentic: { compactions: 2 } } };
    report.perSession[0] = { ...report.perSession[0], ...report.selectedSession };
    report.calls[0] = { ...report.calls[0], title: report.selectedSession.title,
      detail: { available: true, context: { source: 'mcp-stdio', clientRequestId: 'rpc-call' },
        arguments: { available: true, offset: 0, text: '{"file_path":"test.java"}' },
        output: { available: true, offset: 0, text: 'readable tool output', hasMore: true, nextOffset: 32768 } } };
    fixture.componentRef.setInput('usage', report);
    fixture.componentRef.setInput('selectedCall', 'measured-zero');
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    ['Resume conversation title', 'full-session-id-not-abbreviated', 'Compactions: 2', 'total: 168',
      'test.java', 'readable tool output', 'rpc-call'].forEach(value => expect(text).toContain(value));
    spyOn(fixture.componentInstance.contentPageRequested, 'emit');
    const next = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(button => button.textContent?.includes('Read next page'))!;
    next.click();
    expect(fixture.componentInstance.contentPageRequested.emit).toHaveBeenCalledWith({
      sessionId: 'full-session-id-not-abbreviated', invocationId: 'measured-zero', field: 'output', offset: 32768 });
  });

  it('reads local catalog results without inventing token measurements', () => {
    const report = usage();
    report.catalog = { calls: [{ id: 'catalog-call', sessionId: 'full-session-id-not-abbreviated', toolName: 'read',
      toolInput: '{"file_path":"local.java"}', detail: { available: true,
        context: { clientRequestId: 'provider-call' },
        rawOutput: { available: true, offset: 0, text: 'full local result', hasMore: true, nextOffset: 32768 } } }] };
    fixture.componentRef.setInput('usage', report);
    fixture.componentRef.setInput('selectedCall', 'catalog-call');
    fixture.detectChanges();
    const section = fixture.nativeElement.querySelector('[data-testid="catalog-calls"]');
    expect(section.textContent).toContain('local.java');
    expect(section.textContent).toContain('full local result');
    expect(section.textContent).toContain('provider-call');
    spyOn(fixture.componentInstance.contentPageRequested, 'emit');
    Array.from(section.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(button => button.textContent?.includes('Read next page'))!.click();
    expect(fixture.componentInstance.contentPageRequested.emit).toHaveBeenCalledWith({
      sessionId: 'full-session-id-not-abbreviated', invocationId: 'catalog-call', field: 'rawOutput', offset: 32768 });
    expect(report.summary.calls).toBe(3);
  });

  it('shows all measurement provenance and keeps model events separate for the selected call', () => {
    const report = usage();
    report.calls[0] = { ...report.calls[0], requestedToolName: 'functions.read', resolvedToolName: 'read',
      outcome: 'ERROR', disposition: 'SUPPRESSED', errorResponse: true, durationMs: 0, accountingDegraded: true,
      arguments: { tokens: 0, status: 'MEASURED', representation: 'JSON', method: 'exact', tokenizerId: 'tokenizer', tokenizerVersion: 'v2' },
      rawPayload: { tokens: 55, status: 'MEASURED', representation: 'raw', method: 'exact' },
      modelExecutions: [{ executionId: 'model-execution-full-id', inputTokens: 123, outputTokens: 45 }] };
    fixture.componentRef.setInput('usage', report);
    fixture.componentRef.setInput('selectedCall', 'measured-zero');
    fixture.detectChanges();
    const text: string = fixture.nativeElement.querySelector('[data-testid="call-provenance"]').textContent;
    ['functions.read', 'SUPPRESSED', 'ERROR', '0 ms', 'MEASURED', 'JSON', 'exact', 'tokenizer / v2',
      'Raw payload measurement: 55', 'model-execution-full-id', 'separate provider ledger'].forEach(value => expect(text).toContain(value));
    expect(fixture.nativeElement.querySelector('[data-testid="usage-totals"]').textContent).not.toContain('123');
    fixture.componentRef.setInput('selectedCall', 'not-present');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="call-provenance"]')).toBeNull();
  });
});
