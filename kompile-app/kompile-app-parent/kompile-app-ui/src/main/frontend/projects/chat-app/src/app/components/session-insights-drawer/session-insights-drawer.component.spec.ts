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
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject } from 'rxjs';
import { LocalAgentChatService, SessionInsightsSnapshot } from '@shared/services/local-agent-chat.service';
import { LocalAgentMessage, ToolUseEvent, createUserMessage } from '@shared/models/api-models';
import {
  IDLE_REFRESH_MS, INSIGHTS_DRAWER_OPEN_KEY, LIVE_REFRESH_MS, SessionInsightsDrawerComponent, TOOL_REFRESH_MS
} from './session-insights-drawer.component';

/*
 * Each test ends by destroying the drawer inside fakeAsync, which fails the test when a timer is
 * still queued — so every test also checks that the drawer leaves no poll behind.
 */
describe('SessionInsightsDrawerComponent', () => {
  let fixture: ComponentFixture<SessionInsightsDrawerComponent>;
  let chatService: jasmine.SpyObj<LocalAgentChatService>;
  let toolCalls: Subject<ToolUseEvent[]>;
  let replies: Subject<LocalAgentMessage>;
  /** One pending answer per read, in the order the drawer asked for them. */
  let answers: Subject<SessionInsightsSnapshot>[];

  const insights = (lines: string[], live = false): SessionInsightsSnapshot =>
    ({ menu: 'insights', available: true, sessionId: 's1', lines, live });

  /** The streaming message's tool calls, the first `finished` of them completed. */
  const calls = (total: number, finished: number): ToolUseEvent[] =>
    Array.from({ length: total }, (_, i) =>
      ({ tool: 'bash', input: 'ls', callId: `c${i}`, status: i < finished ? 'completed' : 'started' }));

  const replyEnded = () => replies.next(createUserMessage('s1', 'done'));

  beforeEach(() => {
    localStorage.removeItem(INSIGHTS_DRAWER_OPEN_KEY);
    toolCalls = new Subject();
    replies = new Subject();
    answers = [];
    chatService = jasmine.createSpyObj<LocalAgentChatService>('LocalAgentChatService',
      ['getSessionInsights', 'getToolCalls', 'getStreamingComplete']);
    chatService.getToolCalls.and.returnValue(toolCalls.asObservable());
    chatService.getStreamingComplete.and.returnValue(replies.asObservable());
    chatService.getSessionInsights.and.callFake(() => {
      const answer = new Subject<SessionInsightsSnapshot>();
      answers.push(answer);
      return answer.asObservable();
    });
    TestBed.configureTestingModule({
      imports: [SessionInsightsDrawerComponent],
      providers: [{ provide: LocalAgentChatService, useValue: chatService }]
    });
  });

  afterEach(() => localStorage.removeItem(INSIGHTS_DRAWER_OPEN_KEY));

  function create(withSession = true): void {
    fixture = TestBed.createComponent(SessionInsightsDrawerComponent);
    fixture.componentRef.setInput('sessionId', withSession ? 's1' : undefined);
    fixture.componentRef.setInput('workingDirectory', '/work');
    fixture.detectChanges();
  }

  function drawer(): HTMLDetailsElement {
    return fixture.nativeElement.querySelector('[data-testid="session-insights"]');
  }

  /** As a click on the summary does: the element opens or closes, then fires toggle. */
  function toggle(open: boolean): void {
    drawer().open = open;
    drawer().dispatchEvent(new Event('toggle'));
    fixture.detectChanges();
  }

  function answer(index: number, snapshot: SessionInsightsSnapshot): void {
    answers[index].next(snapshot);
    answers[index].complete();
  }

  function reads(): number {
    return chatService.getSessionInsights.calls.count();
  }

  function status(): string | undefined {
    return fixture.nativeElement.querySelector('[role="status"]')?.textContent.trim();
  }

  function text(): string {
    return fixture.nativeElement.textContent;
  }

  it('reads nothing while closed and reads the chat session once opened', fakeAsync(() => {
    create();
    toolCalls.next(calls(1, 1));
    replyEnded();
    tick(IDLE_REFRESH_MS);
    expect(reads()).toBe(0);

    toggle(true);
    expect(reads()).toBe(1);
    expect(chatService.getSessionInsights).toHaveBeenCalledWith('s1', '/work');
    expect(localStorage.getItem(INSIGHTS_DRAWER_OPEN_KEY)).toBe('true');
    fixture.destroy();
  }));

  it('opens as it was left and reads at once', fakeAsync(() => {
    localStorage.setItem(INSIGHTS_DRAWER_OPEN_KEY, 'true');
    create();
    expect(drawer().open).toBeTrue();
    expect(reads()).toBe(1);
    fixture.destroy();
  }));

  it('shows each topic with its summary and dims the detail rows', fakeAsync(() => {
    create();
    toggle(true);
    answer(0, insights([
      'Judge: 1 flagged of 1 verdict · last flag 10:02: bash (high)',
      '↳ deleted files outside the project',
      'Tools: 2 calls, 1 error · p50 40ms p95 90ms',
      'Crawl: no recent crawl jobs']));

    const rows = Array.from<HTMLElement>(fixture.nativeElement.querySelectorAll('.insights-rows li'));
    expect(rows.map(row => row.querySelector('.insights-topic')?.textContent ?? null))
      .toEqual(['Judge:', null, 'Tools:', 'Crawl:']);
    expect(rows.map(row => row.classList.contains('insights-detail'))).toEqual([false, true, false, false]);
    expect(rows[0].textContent).toContain('1 flagged of 1 verdict · last flag 10:02: bash (high)');
    expect(rows[1].textContent!.trim()).toBe('↳ deleted files outside the project');
    expect(status()).toBeUndefined();
    expect(fixture.nativeElement.querySelector('summary').textContent).not.toContain('live');
    fixture.destroy();
  }));

  it('refreshes when a call finishes, not when it starts, at most once per window', fakeAsync(() => {
    create();
    toggle(true);
    answer(0, insights(['Tools: no calls yet']));

    toolCalls.next(calls(1, 0));
    expect(reads()).toBe(1);
    toolCalls.next(calls(1, 1));
    expect(reads()).toBe(2);
    answer(1, insights(['Tools: 1 call']));

    toolCalls.next(calls(2, 1));
    toolCalls.next(calls(2, 2));
    expect(reads()).toBe(2);
    tick(TOOL_REFRESH_MS);
    expect(reads()).toBe(3);
    answer(2, insights(['Tools: 2 calls']));
    fixture.destroy();
  }));

  it('folds refreshes asked for during a read into one read after it', fakeAsync(() => {
    create();
    toggle(true);
    toolCalls.next(calls(1, 1));
    replyEnded();
    expect(reads()).toBe(1);

    answer(0, insights(['Tools: 1 call']));
    expect(reads()).toBe(2);
    answer(1, insights(['Tools: 3 calls']));
    expect(reads()).toBe(2);
    expect(text()).toContain('3 calls');
    fixture.destroy();
  }));

  it('drops the previous session\'s rows and read when the session changes', fakeAsync(() => {
    create();
    toggle(true);
    answer(0, insights(['Tools: 9 calls']));
    replyEnded();
    expect(reads()).toBe(2);

    fixture.componentRef.setInput('sessionId', 's2');
    fixture.detectChanges();
    expect(reads()).toBe(3);
    expect(chatService.getSessionInsights.calls.mostRecent().args).toEqual(['s2', '/work']);
    expect(text()).not.toContain('9 calls');

    answer(1, insights(['Tools: 7 calls']));
    expect(text()).not.toContain('7 calls');
    answer(2, insights(['Tools: 1 call']));
    expect(text()).toContain('Tools: 1 call');
    fixture.destroy();
  }));

  it('says why insights are unavailable, keeping its rows when only a read failed', fakeAsync(() => {
    create();
    toggle(true);
    answer(0, insights(['Tools: 2 calls']));

    replyEnded();
    answers[1].error(new HttpErrorResponse({
      status: 503, statusText: 'Service Unavailable', error: { status: 503, error: 'Service Unavailable' }
    }));
    expect(status()).toBe('Session insights are unavailable (HTTP 503)');
    expect(text()).toContain('2 calls');

    replyEnded();
    answer(2, { menu: 'insights', available: false, status: 'Session insights need the chat session id' });
    expect(status()).toBe('Session insights need the chat session id');
    expect(fixture.nativeElement.querySelectorAll('.insights-rows li').length).toBe(0);
    fixture.destroy();
  }));

  it('reads faster while a crawl is live and stops reading when closed', fakeAsync(() => {
    create();
    toggle(true);
    answer(0, insights(['Crawl: docs 12 of 30 files'], true));
    expect(fixture.nativeElement.querySelector('summary').textContent).toContain('live');
    tick(LIVE_REFRESH_MS - 1);
    expect(reads()).toBe(1);
    tick(1);
    expect(reads()).toBe(2);

    answer(1, insights(['Crawl: no recent crawl jobs']));
    tick(LIVE_REFRESH_MS);
    expect(reads()).toBe(2);
    tick(IDLE_REFRESH_MS - LIVE_REFRESH_MS);
    expect(reads()).toBe(3);

    answer(2, insights(['Crawl: no recent crawl jobs']));
    toggle(false);
    tick(IDLE_REFRESH_MS * 2);
    expect(reads()).toBe(3);
    expect(localStorage.getItem(INSIGHTS_DRAWER_OPEN_KEY)).toBe('false');
    fixture.destroy();
  }));

  it('waits while the page is hidden or its chat is not the active one', fakeAsync(() => {
    const visibility = spyOnProperty(document, 'visibilityState').and.returnValue('hidden');
    create();
    toggle(true);
    expect(reads()).toBe(0);

    visibility.and.returnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(reads()).toBe(1);
    answer(0, insights(['Tools: 1 call']));

    fixture.componentRef.setInput('active', false);
    fixture.detectChanges();
    tick(IDLE_REFRESH_MS);
    replyEnded();
    expect(reads()).toBe(1);

    fixture.componentRef.setInput('active', true);
    fixture.detectChanges();
    expect(reads()).toBe(2);
    fixture.destroy();
  }));

  it('links to the insights page on the chat\'s project, in its own tab', fakeAsync(() => {
    create();
    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('[data-testid="insights-page-link"]');
    expect(link.getAttribute('href')).toBe('#/insights?workingDirectory=%2Fwork');
    expect(link.target).toBe('_blank');

    fixture.componentRef.setInput('workingDirectory', undefined);
    fixture.detectChanges();
    expect(link.getAttribute('href')).toBe('#/insights');
    fixture.destroy();
  }));

  it('asks for a session before reading', fakeAsync(() => {
    create(false);
    toggle(true);
    expect(reads()).toBe(0);
    expect(status()).toBe('Insights appear once this chat has a session.');
    fixture.destroy();
  }));

  it('stops reading once destroyed', fakeAsync(() => {
    create();
    toggle(true);
    answer(0, insights(['Crawl: docs 12 of 30 files'], true));
    fixture.destroy();

    toolCalls.next(calls(1, 1));
    replyEnded();
    document.dispatchEvent(new Event('visibilitychange'));
    expect(reads()).toBe(1);
  }));
});
