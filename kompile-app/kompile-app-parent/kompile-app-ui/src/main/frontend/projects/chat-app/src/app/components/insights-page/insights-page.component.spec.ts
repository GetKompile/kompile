/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { ComponentFixture, TestBed, fakeAsync, flush, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, NavigationExtras, ParamMap, Params, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject, Observable, Subject, of, throwError } from 'rxjs';
import { map } from 'rxjs/operators';
import {
  InsightsSettings, InsightsSettingsView, InsightsTopicReport, LocalAgentChatService, ToolUsageReport
} from '@shared/services/local-agent-chat.service';
import { AUTO_REFRESH_MS, InsightsPageComponent } from './insights-page.component';

/** ActivatedRoute whose query parameters the Router fake merges as a real navigation would. */
class RouteStub {
  private readonly params$ = new BehaviorSubject<Params>({});
  readonly queryParamMap: Observable<ParamMap> = this.params$.pipe(map(params => convertToParamMap(params)));

  get params(): Params {
    return this.params$.value;
  }

  setQueryParams(params: Params): void {
    this.params$.next(params);
  }

  /** As `queryParamsHandling: 'merge'` does: null drops a parameter. */
  mergeQueryParams(changes: Params): void {
    const next: Params = { ...this.params$.value };
    for (const [key, value] of Object.entries(changes)) {
      if (value == null) {
        delete next[key];
      } else {
        next[key] = value;
      }
    }
    this.params$.next(next);
  }
}

const MIB = 1024 * 1024;
const FILE = '/home/user/.kompile/config/insights.json';
const DEFAULTS: InsightsSettings = {
  defaultWindowDays: 7, maxRows: 10, maxExamples: 5, maxSessions: 200, maxBytesPerFile: 4 * MIB,
  maxToolIndexBytes: 256 * MIB, sparklineBuckets: 14, sessionPanel: true
};

/*
 * Each test ends by destroying the page inside fakeAsync, which fails the test when a timer is
 * still queued — so every test also checks that the page leaves no refresh behind.
 */
describe('InsightsPageComponent', () => {
  let fixture: ComponentFixture<InsightsPageComponent>;
  let chatService: jasmine.SpyObj<LocalAgentChatService>;
  let router: jasmine.SpyObj<Router>;
  let route: RouteStub;
  /** One pending answer per report read, in the order the page asked for them. */
  let answers: Subject<InsightsTopicReport>[];

  const report = (topic: string, extra: Partial<InsightsTopicReport> = {}): InsightsTopicReport => ({
    menu: 'insights', topic, available: true, headline: `${topic}: 3 sessions`,
    text: `${topic}: 3 sessions\n\n  tool   calls\n  bash      12\n`, ...extra
  });

  const view = (settings: Partial<InsightsSettings> = {}, warning?: string): InsightsSettingsView =>
    ({ file: FILE, settings: { ...DEFAULTS, ...settings }, defaults: DEFAULTS, ...(warning ? { warning } : {}) });

  beforeEach(() => {
    answers = [];
    route = new RouteStub();
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.callFake((_commands: unknown[], extras?: NavigationExtras) => {
      if (extras?.queryParams) {
        route.mergeQueryParams(extras.queryParams);
      }
      return Promise.resolve(true);
    });
    chatService = jasmine.createSpyObj<LocalAgentChatService>('LocalAgentChatService',
      ['getInsightsReport', 'getInsightsSettings', 'saveInsightsSettings', 'getToolInvocationPage']);
    chatService.getInsightsReport.and.callFake(() => {
      const answer = new Subject<InsightsTopicReport>();
      answers.push(answer);
      return answer.asObservable();
    });
    TestBed.configureTestingModule({
      imports: [InsightsPageComponent, NoopAnimationsModule],
      providers: [
        { provide: LocalAgentChatService, useValue: chatService },
        { provide: ActivatedRoute, useValue: route },
        { provide: Router, useValue: router }
      ]
    });
  });

  function create(params: Params = {}): void {
    route.setQueryParams(params);
    fixture = TestBed.createComponent(InsightsPageComponent);
    fixture.detectChanges();
    // ngModel writes a value to its control a microtask after it changes.
    tick();
  }

  function answer(index: number, value: InsightsTopicReport): void {
    answers[index].next(value);
    answers[index].complete();
    fixture.detectChanges();
    tick();
  }

  function fail(index: number, error: HttpErrorResponse): void {
    answers[index].error(error);
    fixture.detectChanges();
    tick();
  }

  function reads(): number {
    return chatService.getInsightsReport.calls.count();
  }

  function lastRead(): unknown[] {
    return chatService.getInsightsReport.calls.mostRecent().args;
  }

  function query<T extends Element = HTMLElement>(selector: string): T | null {
    return fixture.nativeElement.querySelector(selector);
  }

  function textOf(selector: string): string | undefined {
    return query(selector)?.textContent?.trim();
  }

  function tab(topic: string): HTMLElement {
    return query(`a[data-topic="${topic}"]`)!;
  }

  function open(topic: string): void {
    tab(topic).click();
    fixture.detectChanges();
    tick();
  }

  function type(selector: string, value: string): void {
    const input = query<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function click(testId: string): void {
    query<HTMLElement>(`[data-testid="${testId}"]`)!.click();
    fixture.detectChanges();
    tick();
  }

  function check(selector: string): void {
    query<HTMLInputElement>(`${selector} input[type="checkbox"]`)!.click();
    fixture.detectChanges();
    tick();
  }

  function chooseWindow(label: string): void {
    query<HTMLElement>('[data-testid="insights-window"] .mat-mdc-select-trigger')!.click();
    fixture.detectChanges();
    tick(500);
    const option = Array.from(document.querySelectorAll<HTMLElement>('mat-option'))
      .find(candidate => candidate.textContent?.trim() === label);
    expect(option).withContext(`window option ${label}`).toBeDefined();
    option!.click();
    fixture.detectChanges();
    tick(500);
  }

  /** As a click on the summary does: the element opens or closes, then fires toggle. */
  function toggleSettings(open: boolean): void {
    const details = query<HTMLDetailsElement>('[data-testid="insights-settings"]')!;
    details.open = open;
    details.dispatchEvent(new Event('toggle'));
    fixture.detectChanges();
    tick();
  }

  function setting(key: string): HTMLInputElement {
    return query<HTMLInputElement>(`input[data-setting="${key}"]`)!;
  }

  const tokenUsage = (changes: Partial<ToolUsageReport> = {}): ToolUsageReport => ({
    summary: { calls: 12, argumentsTokens: 90, payloadTokens: 120, unmeasuredPayloadCalls: 2, partialPayloadCalls: 1, degradedCalls: 1 },
    perTool: [{ tool: 'read', calls: 12, argumentsTokens: 90, payloadTokens: 120, unmeasuredPayloadCalls: 2, partialPayloadCalls: 1 }],
    perSession: [{ sessionId: 'full-session-id-123456789', calls: 12, argumentsTokens: 90, payloadTokens: 120, unmeasuredPayloadCalls: 2, partialPayloadCalls: 1 }],
    calls: [{ invocationId: 'full-invocation-id-987654321', sessionId: 'full-session-id-123456789', tool: 'read',
      requestedToolName: 'functions.read', resolvedToolName: 'read', outcome: 'SUCCESS', disposition: 'DELIVERED', durationMs: 42,
      arguments: { tokens: 0, status: 'FULLY_MEASURED', representation: 'JSON', method: 'tokenizer', tokenizerId: 'local', tokenizerVersion: '1' },
      payload: { status: 'UNAVAILABLE' }, rawPayload: { tokens: 17, status: 'FULLY_MEASURED', representation: 'raw text' },
      modelExecutions: [{ inputTokens: 500, outputTokens: 10 }], accountingDegraded: true }],
    offset: 0, limit: 5, totalCalls: 12, hasMore: true,
    ...changes
  });

  it('defaults tools to tokens but retains counts/latency mode and the window', fakeAsync(() => {
    create({ topic: 'tools', window: 'last 30 days', workingDirectory: '/work/app' });
    expect(lastRead()).toEqual(['tools', 'tokens last 30 days', '/work/app']);
    answer(0, report('tools'));
    click('tools-count-mode');
    expect(lastRead()).toEqual(['tools', 'counts and latency, last 30 days', '/work/app']);
    expect(query('[data-testid="tool-filter"]')).toBeNull();
    answer(1, report('tools'));
    click('tools-token-mode');
    expect(lastRead()).toEqual(['tools', 'tokens last 30 days', '/work/app']);
    answer(2, report('tools'));
    fixture.destroy();
  }));

  it('switches a token drilldown to counts without token-only selectors and preserves session scope', fakeAsync(() => {
    create({ topic: 'tools', question: 'tokens session:full-session tool:read call:full-call offset:5',
      window: 'last 30 days', workingDirectory: '/work/app' });
    answer(0, report('tools', { usage: tokenUsage() }));
    click('tools-count-mode');
    expect(lastRead()).toEqual(['tools', 'counts and latency session:full-session last 30 days', '/work/app']);
    answer(1, report('tools', { usage: tokenUsage() }));
    click('usage-call');
    expect(lastRead()[1]).toBe('tokens session:full-session call:full-invocation-id-987654321 last 30 days');
    answer(2, report('tools', { usage: tokenUsage() }));
    expect(query('[data-testid="tool-filter"]')).not.toBeNull();
    fixture.destroy();
  }));

  it('lets a selected window override the time phrase in a session bookmark', fakeAsync(() => {
    create({ topic: 'tools', question: 'tokens last 7 days session:full-session',
      window: 'last 30 days', workingDirectory: '/work/app' });
    expect(lastRead()).toEqual(['tools', 'tokens session:full-session last 30 days', '/work/app']);
    answer(0, report('tools'));
    fixture.destroy();
  }));

  it('rereads on tool/session navigation and discards stale filter responses', fakeAsync(() => {
    create({ topic: 'tools', question: 'tokens last 7 days', workingDirectory: '/work/app' });
    answer(0, report('tools', { usage: tokenUsage() }));
    click('usage-tool');
    expect(lastRead()).toEqual(['tools', 'tokens last 7 days tool:read', '/work/app']);
    expect(query('[data-testid="tool-usage-details"]')).toBeNull();
    expect(route.params['question']).toBe('tokens last 7 days tool:read');
    // Navigate while this tool read is still in flight, as a bookmark/browser navigation can.
    route.setQueryParams({ ...route.params, question: 'tokens last 7 days tool:read session:full-session-id-123456789' });
    expect(answers[1].observed).toBeFalse();
    answers[1].next(report('tools', { headline: 'STALE FILTER' }));
    answer(2, report('tools', { usage: tokenUsage() }));
    expect(textOf('.report-headline')).not.toBe('STALE FILTER');
    expect(textOf('[data-testid="tool-breadcrumbs"]')).toContain('full-session-id-123456789');
    click('usage-session');
    expect(lastRead()[1]).toBe('tokens last 7 days session:full-session-id-123456789 tool:read');
    answer(3, report('tools', { usage: tokenUsage() }));
    fixture.destroy();
  }));

  it('paginates via the URL and rereads a full invocation before showing provenance', fakeAsync(() => {
    create({ topic: 'tools', question: 'tokens', window: 'last 30 days', workingDirectory: '/work/app' });
    answer(0, report('tools', { usage: tokenUsage() }));
    click('usage-next');
    expect(lastRead()).toEqual(['tools', 'tokens offset:5 last 30 days', '/work/app']);
    expect(route.params['question']).toBe('tokens offset:5');
    answer(1, report('tools', { usage: tokenUsage({ offset: 5 }) }));
    expect(textOf('[data-testid="usage-totals"]')).toContain('12 filtered calls');
    click('usage-previous');
    expect(lastRead()).toEqual(['tools', 'tokens last 30 days', '/work/app']);
    answer(2, report('tools', { usage: tokenUsage() }));
    click('usage-call');
    expect(lastRead()).toEqual(['tools', 'tokens call:full-invocation-id-987654321 last 30 days', '/work/app']);
    expect(query('[data-testid="call-provenance"]')).toBeNull();
    answer(3, report('tools', { usage: tokenUsage({ totalCalls: 1, hasMore: false }) }));
    const provenance = textOf('[data-testid="call-provenance"]')!;
    expect(provenance).toContain('full-invocation-id-987654321');
    expect(provenance).toContain('functions.read');
    expect(provenance).toContain('DELIVERED');
    expect(provenance).toContain('42 ms');
    expect(provenance).toContain('Raw payload measurement: 17');
    expect(provenance).toContain('Tokenizer ID / version');
    expect(provenance).toContain('separate provider ledger');
    click('back-tool-filter');
    expect(lastRead()).toEqual(['tools', 'tokens last 30 days', '/work/app']);
    answer(4, report('tools', { usage: tokenUsage() }));
    fixture.destroy();
  }));

  it('submits visible token filters and charts the server per-tool token series', fakeAsync(() => {
    create({ topic: 'tools', question: 'tokens', workingDirectory: '/work/a project' });
    answer(0, report('tools', { usage: tokenUsage(), chart: {
      v: 1, kind: 'line', title: 'Measured tool payload tokens', unit: 'tokens', labels: ['1', '2', '3'],
      series: [{ name: 'read', values: [0, null, 120] }]
    } }));
    expect(query('[data-testid="tool-call-chart"]')).not.toBeNull();
    type('[data-testid="tool-filter"]', 'functions.read');
    type('[data-testid="session-filter"]', 'full-session');
    type('[data-testid="call-filter"]', 'full-call');
    click('apply-tool-filters');
    expect(lastRead()).toEqual(['tools', 'tokens session:full-session tool:functions.read call:full-call', '/work/a project']);
    expect(route.params['question']).toBe('tokens session:full-session tool:functions.read call:full-call');
    expect(query('[data-testid="tool-call-chart"]')).toBeNull();
    answer(1, report('tools', { usage: tokenUsage() }));
    fixture.destroy();
  }));

  it('opens a selected-call bookmark and resets only filters, retaining context', fakeAsync(() => {
    create({ topic: 'tools', question: 'tokens last 7 days tool:read session:full-session call:full-call offset:5',
      window: 'last 30 days', workingDirectory: '/work/app' });
    expect(query<HTMLInputElement>('[data-testid="call-filter"]')!.value).toBe('full-call');
    answer(0, report('tools', { usage: tokenUsage() }));
    click('reset-tool-filters');
    expect(lastRead()).toEqual(['tools', 'tokens last 30 days', '/work/app']);
    expect(route.params).toEqual({ topic: 'tools', question: 'tokens last 7 days', window: 'last 30 days', workingDirectory: '/work/app' });
    answer(1, report('tools'));
    fixture.destroy();
  }));

  it('reads the overview when it opens and shows the text under the headline', fakeAsync(() => {
    create();
    expect(reads()).toBe(1);
    expect(lastRead()).toEqual(['overview', undefined, undefined]);
    expect(query('mat-progress-bar')).not.toBeNull();
    expect(tab('overview').getAttribute('aria-selected')).toBe('true');

    answer(0, report('overview'));
    expect(query('mat-progress-bar')).toBeNull();
    expect(textOf('.report-headline')).toBe('overview: 3 sessions');
    // The headline line and the blank line under it go; the table keeps its indentation.
    expect(query('.report-text')!.textContent).toBe('  tool   calls\n  bash      12');
    expect(textOf('.insights-report .insights-meta')).toMatch(/^Read at \d\d:\d\d:\d\d$/);
    expect(chatService.getInsightsSettings).not.toHaveBeenCalled();
    fixture.destroy();
  }));

  it('opens the topic, question, window and directory the URL names', fakeAsync(() => {
    create({ topic: 'judge', question: 'flags for bash', window: 'last 30 days', workingDirectory: '/work/app' });
    expect(reads()).toBe(1);
    expect(lastRead()).toEqual(['judge', 'flags for bash, last 30 days', '/work/app']);
    expect(tab('judge').getAttribute('aria-selected')).toBe('true');
    expect(tab('overview').getAttribute('aria-selected')).toBe('false');
    expect(query<HTMLInputElement>('[data-testid="insights-question"]')!.value).toBe('flags for bash');
    expect(query<HTMLInputElement>('[data-testid="insights-directory"]')!.value).toBe('/work/app');
    expect(textOf('.topic-about')).toContain('Judge verdicts');
    answer(0, report('judge'));
    fixture.destroy();
  }));

  it('ignores a topic or window the page does not offer', fakeAsync(() => {
    create({ topic: 'weather', window: 'last fortnight' });
    expect(lastRead()).toEqual(['overview', undefined, undefined]);
    answer(0, report('overview'));
    fixture.destroy();
  }));

  it('keeps a question per topic and the open one in the URL', fakeAsync(() => {
    create();
    answer(0, report('overview'));

    open('judge');
    expect(reads()).toBe(2);
    expect(lastRead()).toEqual(['judge', undefined, undefined]);
    expect(router.navigate).toHaveBeenCalledWith([], jasmine.objectContaining({
      queryParams: { topic: 'judge', question: null, window: null, workingDirectory: null },
      queryParamsHandling: 'merge',
      replaceUrl: true
    }));
    answer(1, report('judge'));

    type('[data-testid="insights-question"]', '  flags for bash ');
    click('insights-ask');
    expect(reads()).toBe(3);
    expect(lastRead()).toEqual(['judge', 'flags for bash', undefined]);
    expect(route.params).toEqual({ topic: 'judge', question: 'flags for bash' });
    answer(2, report('judge'));

    // A tab whose report answers its question is not read again on the way back.
    open('overview');
    expect(route.params).toEqual({ topic: 'overview' });
    expect(query<HTMLInputElement>('[data-testid="insights-question"]')!.value).toBe('');
    open('judge');
    expect(reads()).toBe(3);
    expect(route.params).toEqual({ topic: 'judge', question: 'flags for bash' });
    expect(query<HTMLInputElement>('[data-testid="insights-question"]')!.value).toBe('flags for bash');
    fixture.destroy();
  }));

  it('adds the window to the question and reads again when the window changes', fakeAsync(() => {
    create({ topic: 'tools', question: 'slowest calls of the read tool' });
    answer(0, report('tools'));

    chooseWindow('Last 24 hours');
    expect(reads()).toBe(2);
    expect(lastRead()).toEqual(['tools', 'slowest calls of the read tool, last 24 hours', undefined]);
    expect(route.params)
      .toEqual({ topic: 'tools', question: 'slowest calls of the read tool', window: 'last 24 hours' });
    answer(1, report('tools'));

    chooseWindow('Topic default');
    expect(reads()).toBe(3);
    expect(lastRead()).toEqual(['tools', 'slowest calls of the read tool', undefined]);
    expect(route.params).toEqual({ topic: 'tools', question: 'slowest calls of the read tool' });
    answer(2, report('tools'));
    fixture.destroy();
  }));

  it('reads the report again on Refresh, showing the last one until the new one arrives', fakeAsync(() => {
    create();
    answer(0, report('overview'));

    click('insights-refresh');
    expect(reads()).toBe(2);
    expect(lastRead()).toEqual(['overview', undefined, undefined]);
    expect(textOf('.report-headline')).toBe('overview: 3 sessions');

    answer(1, report('overview', { headline: 'overview: 4 sessions', text: 'overview: 4 sessions\n' }));
    expect(textOf('.report-headline')).toBe('overview: 4 sessions');
    expect(query('.report-text')).toBeNull();
    fixture.destroy();
  }));

  it('clears the report of another question while the new one is read', fakeAsync(() => {
    create();
    answer(0, report('overview'));

    type('[data-testid="insights-question"]', 'last 3 days');
    click('insights-ask');
    expect(query('[data-testid="insights-report"]')).toBeNull();
    expect(query('mat-progress-bar')).not.toBeNull();

    answer(1, report('overview', { headline: 'Overview, last 3 days: 5 topics' }));
    expect(textOf('.report-headline')).toBe('Overview, last 3 days: 5 topics');
    fixture.destroy();
  }));

  it('keeps the whole text when its first line is not the headline', fakeAsync(() => {
    create();
    answer(0, report('overview', { headline: 'Overview, last 7 days: 2 topics', text: 'Judge: 1 flag\nTools: 9 calls\n' }));
    expect(query('.report-text')!.textContent).toBe('Judge: 1 flag\nTools: 9 calls');

    click('insights-refresh');
    answer(1, report('overview', { headline: 'Judge', text: 'Judge: 1 flag' }));
    expect(query('.report-text')!.textContent).toBe('Judge: 1 flag');
    fixture.destroy();
  }));

  it('says why a report is unavailable', fakeAsync(() => {
    create({ topic: 'crawl' });
    answer(0, { menu: 'insights', topic: 'crawl', available: false, status: 'No crawl jobs are recorded for /work' });
    expect(textOf('.insights-report [role="status"]')).toBe('No crawl jobs are recorded for /work');
    expect(query('.report-headline')).toBeNull();
    expect(query('.report-text')).toBeNull();

    click('insights-refresh');
    answer(1, { menu: 'insights', topic: 'crawl', available: false });
    expect(textOf('.insights-report [role="status"]')).toBe('This report is unavailable');
    fixture.destroy();
  }));

  it('draws the report\'s chart above its text', fakeAsync(() => {
    create({ topic: 'tests' });
    answer(0, report('tests', {
      chart: {
        v: 1, kind: 'line', title: 'Pass rate for core', unit: '%', labels: ['1', '2', '3'],
        series: [{ name: 'passed', values: [90, null, 87.5] }]
      }
    }));
    const chart = query('[data-testid="tool-call-chart"]');
    expect(chart).not.toBeNull();
    expect(chart!.getAttribute('data-kind')).toBe('line');
    expect(chart!.compareDocumentPosition(query('.report-text')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    fixture.destroy();
  }));

  it('says why a read failed, keeping the last report of the same read', fakeAsync(() => {
    create();
    answer(0, report('overview'));

    const failures: [HttpErrorResponse, string][] = [
      [new HttpErrorResponse({ status: 0 }), 'This report could not be read: the chat server is unreachable'],
      [new HttpErrorResponse({ status: 400, error: { ok: false, message: 'Not a registered project: /tmp/x' } }),
        'This report could not be read: Not a registered project: /tmp/x'],
      [new HttpErrorResponse({ status: 503, error: { status: 503, error: 'Service Unavailable', message: '' } }),
        'This report could not be read: the Kompile CLI harness is unavailable'],
      [new HttpErrorResponse({ status: 500, error: 'Internal Server Error' }), 'This report could not be read (HTTP 500)']
    ];
    failures.forEach(([error, message], index) => {
      click('insights-refresh');
      fail(index + 1, error);
      expect(textOf('mat-tab-nav-panel [role="alert"]')).toBe(message);
      expect(textOf('.report-headline')).toBe('overview: 3 sessions');
    });
    fixture.destroy();
  }));

  it('reads a tab again when it opens after its read failed', fakeAsync(() => {
    create();
    fail(0, new HttpErrorResponse({ status: 0 }));
    open('judge');
    answer(1, report('judge'));

    open('overview');
    expect(reads()).toBe(3);
    expect(lastRead()).toEqual(['overview', undefined, undefined]);
    expect(query('mat-tab-nav-panel [role="alert"]')).toBeNull();
    answer(2, report('overview'));
    fixture.destroy();
  }));

  it('reads the open report every 10 seconds by default', fakeAsync(() => {
    create();
    expect(fixture.componentInstance.autoRefresh).toBeTrue();
    expect(AUTO_REFRESH_MS).toBe(10_000);
    answer(0, report('overview'));
    tick(AUTO_REFRESH_MS);
    expect(reads()).toBe(2);
    // No read is stacked on one in flight; the next waits a full period after it answers.
    tick(AUTO_REFRESH_MS);
    expect(reads()).toBe(2);
    answer(1, report('overview'));
    tick(AUTO_REFRESH_MS - 1);
    expect(reads()).toBe(2);
    tick(1);
    expect(reads()).toBe(3);
    expect(lastRead()).toEqual(['overview', undefined, undefined]);
    answer(2, report('overview'));

    check('[data-testid="insights-auto-refresh"]');
    tick(AUTO_REFRESH_MS * 2);
    expect(reads()).toBe(3);
    fixture.destroy();
  }));

  it('waits while the page is hidden and reads once it shows again', fakeAsync(() => {
    const visibility = spyOnProperty(document, 'visibilityState').and.returnValue('visible');
    create();
    answer(0, report('overview'));
    visibility.and.returnValue('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    tick(AUTO_REFRESH_MS * 2);
    expect(reads()).toBe(1);

    visibility.and.returnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(reads()).toBe(2);
    answer(1, report('overview'));
    fixture.destroy();
  }));

  it('stops reading once destroyed', fakeAsync(() => {
    create();
    answer(0, report('overview'));
    click('insights-refresh');
    fixture.destroy();

    expect(answers[1].observed).toBeFalse();
    tick(AUTO_REFRESH_MS);
    document.dispatchEvent(new Event('visibilitychange'));
    expect(reads()).toBe(2);
  }));

  it('loads insights.json the first time the limits open, with byte counts in MiB', fakeAsync(() => {
    chatService.getInsightsSettings.and.returnValue(of(view({ maxBytesPerFile: 512 * 1024 })));
    create();
    answer(0, report('overview'));

    toggleSettings(true);
    expect(chatService.getInsightsSettings).toHaveBeenCalledTimes(1);
    expect(setting('maxRows').value).toBe('10');
    expect(setting('maxBytesPerFile').value).toBe('0.5');
    expect(setting('maxToolIndexBytes').value).toBe('256');
    expect(textOf('.insights-settings .insights-meta')).toBe(FILE);
    expect(query('.insights-settings [role="alert"]')).toBeNull();

    toggleSettings(false);
    toggleSettings(true);
    expect(chatService.getInsightsSettings).toHaveBeenCalledTimes(1);
    fixture.destroy();
  }));

  it('shows the warning insights.json was read with', fakeAsync(() => {
    chatService.getInsightsSettings.and.returnValue(of(view({}, `${FILE} is not a JSON object; using defaults`)));
    create();
    answer(0, report('overview'));
    toggleSettings(true);
    expect(textOf('.insights-settings [role="alert"]')).toBe(`${FILE} is not a JSON object; using defaults`);
    fixture.destroy();
  }));

  it('saves only the limits that changed, then reads every report again', fakeAsync(() => {
    chatService.getInsightsSettings.and.returnValue(of(view()));
    chatService.saveInsightsSettings.and.returnValue(
      of(view({ maxRows: 20, maxBytesPerFile: 8 * MIB, sessionPanel: false })));
    create();
    answer(0, report('overview'));
    open('judge');
    answer(1, report('judge'));
    open('overview');
    expect(reads()).toBe(2);

    toggleSettings(true);
    type('input[data-setting="maxRows"]', '20');
    type('input[data-setting="maxBytesPerFile"]', '8');
    check('mat-checkbox[data-setting="sessionPanel"]');
    click('insights-settings-save');
    expect(chatService.saveInsightsSettings)
      .toHaveBeenCalledOnceWith({ maxRows: 20, maxBytesPerFile: 8 * MIB, sessionPanel: false });
    expect(textOf('.insights-settings [role="status"]')).toBe(`Saved to ${FILE}.`);

    // The open report at once, keeping the old one on screen until the new one answers.
    expect(reads()).toBe(3);
    expect(lastRead()).toEqual(['overview', undefined, undefined]);
    expect(textOf('.report-headline')).toBe('overview: 3 sessions');
    answer(2, report('overview'));
    // Another tab when it opens.
    open('judge');
    expect(reads()).toBe(4);
    answer(3, report('judge'));

    click('insights-settings-save');
    expect(chatService.saveInsightsSettings).toHaveBeenCalledTimes(1);
    expect(textOf('.insights-settings [role="status"]')).toBe('Nothing changed.');
    fixture.destroy();
  }));

  it('pages exact local catalog content and preserves the page across live refreshes', fakeAsync(() => {
    create({ topic: 'tools', question: 'tokens session:session call:catalog-call' });
    const usage: ToolUsageReport = {
      summary: { calls: 0, unmeasuredPayloadCalls: 0, partialPayloadCalls: 0 },
      perTool: [], perSession: [], calls: [], offset: 0, limit: 10, totalCalls: 0, hasMore: false,
      catalog: { calls: [{ id: 'catalog-call', sessionId: 'session', toolName: 'read',
        detail: { available: true, output: { available: true, offset: 0, text: 'first page' } } }] }
    };
    answer(0, report('tools', { usage }));
    chatService.getToolInvocationPage.and.returnValue(of({ available: true, offset: 32768, text: 'next page' }));
    fixture.componentInstance.readContentPage({ sessionId: 'session', invocationId: 'catalog-call', field: 'output', offset: 32768 });
    fixture.detectChanges();
    expect(chatService.getToolInvocationPage).toHaveBeenCalledOnceWith('session', 'catalog-call', 'output', 32768);
    expect(fixture.nativeElement.textContent).toContain('next page');
    tick(AUTO_REFRESH_MS);
    answer(1, report('tools', { usage: { ...usage, catalog: { calls: [{ ...usage.catalog!.calls[0],
      detail: { available: true, output: { available: true, offset: 0, text: 'first page' } } }] } } }));
    expect(fixture.componentInstance.state.report!.usage!.catalog!.calls[0].detail!.output!.offset).toBe(32768);
    fixture.destroy();
  }));

  it('refuses a limit insights.json would reject, naming it', fakeAsync(() => {
    chatService.getInsightsSettings.and.returnValue(of(view()));
    create();
    answer(0, report('overview'));
    toggleSettings(true);

    const refusals: [string, string, string][] = [
      ['maxRows', '0', 'Table rows must be a positive whole number'],
      ['maxRows', '2.5', 'Table rows must be a positive whole number'],
      ['maxRows', '', 'Table rows must be a positive whole number'],
      ['maxRows', '3000000000', 'Table rows must be a positive whole number'],
      ['maxBytesPerFile', '-1', 'Judge log tail (MiB) must be a positive number of MiB'],
      ['maxBytesPerFile', 'lots', 'Judge log tail (MiB) must be a positive number of MiB']
    ];
    for (const [key, shown, message] of refusals) {
      const loaded = setting(key).value;
      type(`input[data-setting="${key}"]`, shown);
      click('insights-settings-save');
      expect(textOf('.insights-settings [role="alert"]')).withContext(`${key} = "${shown}"`).toBe(message);
      type(`input[data-setting="${key}"]`, loaded);
    }
    expect(chatService.saveInsightsSettings).not.toHaveBeenCalled();
    expect(reads()).toBe(1);
    fixture.destroy();
  }));

  it('fills in the defaults, and Save writes the ones that differ', fakeAsync(() => {
    chatService.getInsightsSettings.and.returnValue(of(view({ maxRows: 20, sparklineBuckets: 30 })));
    chatService.saveInsightsSettings.and.returnValue(of(view()));
    create();
    answer(0, report('overview'));
    toggleSettings(true);
    expect(setting('maxRows').value).toBe('20');

    click('insights-settings-defaults');
    expect(setting('maxRows').value).toBe('10');
    expect(textOf('.insights-settings [role="status"]')).toBe('Defaults filled in; Save writes the ones that differ.');
    expect(chatService.saveInsightsSettings).not.toHaveBeenCalled();

    click('insights-settings-save');
    expect(chatService.saveInsightsSettings).toHaveBeenCalledOnceWith({ maxRows: 10, sparklineBuckets: 14 });
    answer(1, report('overview'));
    fixture.destroy();
  }));

  it('says why the limits could not be read or saved', fakeAsync(() => {
    chatService.getInsightsSettings.and.returnValues(
      throwError(() => new HttpErrorResponse({ status: 0 })), of(view()));
    chatService.saveInsightsSettings.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { ok: false, message: 'maxRows must be a positive integer' }
    })));
    create();
    answer(0, report('overview'));

    toggleSettings(true);
    expect(textOf('.insights-settings [role="alert"]'))
      .toBe('Insights settings could not be read: the chat server is unreachable');
    expect(query('input[data-setting="maxRows"]')).toBeNull();

    // Opening the limits again retries a read that failed.
    toggleSettings(false);
    toggleSettings(true);
    expect(chatService.getInsightsSettings).toHaveBeenCalledTimes(2);
    expect(query('.insights-settings [role="alert"]')).toBeNull();

    type('input[data-setting="maxRows"]', '12');
    click('insights-settings-save');
    expect(textOf('.insights-settings [role="alert"]'))
      .toBe('Insights settings could not be saved: maxRows must be a positive integer');
    expect(query<HTMLButtonElement>('[data-testid="insights-settings-save"]')!.disabled).toBeFalse();
    expect(reads()).toBe(1);
    fixture.destroy();
  }));
});
