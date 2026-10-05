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
import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnChanges, OnDestroy, OnInit, SimpleChanges
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription, asyncScheduler, merge } from 'rxjs';
import { distinctUntilChanged, filter, map, throttleTime } from 'rxjs/operators';
import { LocalAgentChatService, SessionInsightsSnapshot } from '@shared/services/local-agent-chat.service';

/** One drawer row: a topic's summary ("Judge: …") or, dimmed, a detail of the row above it ("↳ …"). */
export interface InsightRow {
  topic?: string;
  text: string;
  detail: boolean;
}

/** Remembers whether the drawer is open, so it stays open across chats and reloads. */
export const INSIGHTS_DRAWER_OPEN_KEY = 'kompile.chat.insights-drawer.open';
/** How often the drawer reads while a crawl runs, so its progress moves. */
export const LIVE_REFRESH_MS = 10_000;
/** How often the drawer reads otherwise, for what changes between turns: milestones, crawls. */
export const IDLE_REFRESH_MS = 30_000;
/** Finished tool calls refresh at most this often; the end of a reply always refreshes. */
export const TOOL_REFRESH_MS = 3_000;
/** The topic of a summary row, the word before its first ": ". */
const TOPIC = /^([A-Z][A-Za-z]{0,15}): (.*)$/;

/**
 * The chat's insights drawer: the rows the terminal's dashboard area shows for this session —
 * judge flags, tool counts and latency, the last test milestone and crawl progress. Every read
 * starts a short CLI process, so the drawer reads only while it is open, its chat is the active
 * one and the page is visible: when it opens, after tool calls finish (throttled), when a reply
 * ends, and on a timer that runs faster while a crawl is live. Its link opens the insights page,
 * every topic across the project's chats, for the chat's project.
 */
@Component({
  selector: 'app-session-insights-drawer',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <details class="session-insights" data-testid="session-insights" [open]="open" (toggle)="onToggle($event)">
      <summary>Session insights<span *ngIf="live" class="insights-live"> · live</span><span
        *ngIf="loading" class="insights-meta"> · refreshing…</span><span
        *ngIf="!loading && updatedAt" class="insights-meta"> · {{ updatedAt | date:'HH:mm:ss' }}</span></summary>
      <p *ngIf="!sessionId" class="insights-status" role="status">Insights appear once this chat has a session.</p>
      <p *ngIf="sessionId && status" class="insights-status" role="status">{{ status }}</p>
      <ul *ngIf="rows.length" class="insights-rows">
        <li *ngFor="let row of rows" [class.insights-detail]="row.detail"><span
          *ngIf="row.topic" class="insights-topic">{{ row.topic }}:</span> {{ row.text }}</li>
      </ul>
      <a class="insights-page-link" data-testid="insights-page-link" [href]="pageHref" target="_blank"
         rel="noopener">All reports for this project</a>
      <a *ngIf="sessionId" class="insights-page-link" data-testid="tool-token-breakdown-link"
         [href]="toolTokenHref" target="_blank" rel="noopener">Tool token breakdown</a>
    </details>
  `,
  styleUrls: ['./session-insights-drawer.component.css']
})
export class SessionInsightsDrawerComponent implements OnInit, OnChanges, OnDestroy {
  /** The id the harness keys this chat's session by. */
  @Input() sessionId?: string;
  @Input() workingDirectory?: string;
  /** False while the chat is a hidden workspace tab: the drawer stops reading until it is shown. */
  @Input() active = true;

  open = false;
  loading = false;
  live = false;
  rows: InsightRow[] = [];
  status?: string;
  updatedAt?: Date;

  private readonly triggers = new Subscription();
  private inFlight?: Subscription;
  /** A refresh asked for while a read was in flight; it runs once that read settles. */
  private rerun = false;
  private timer?: ReturnType<typeof setTimeout>;
  private destroyed = false;
  private readonly onVisibilityChange = () => {
    if (document.visibilityState === 'hidden') this.clearTimer();
    else this.refresh();
  };

  constructor(private chatService: LocalAgentChatService, private cdr: ChangeDetectorRef) {}

  /** The insights page on this chat's project, opened in its own tab so the chat keeps its place. */
  get pageHref(): string {
    const directory = this.workingDirectory?.trim();
    return directory ? `#/insights?workingDirectory=${encodeURIComponent(directory)}` : '#/insights';
  }

  get toolTokenHref(): string {
    const question = `tokens last 7 days session:${this.sessionId}`;
    const directory = this.workingDirectory?.trim();
    return `#/insights?topic=tools&question=${encodeURIComponent(question)}`
      + (directory ? `&workingDirectory=${encodeURIComponent(directory)}` : '');
  }

  ngOnInit(): void {
    this.open = readOpen();
    const finishedCalls = this.chatService.getToolCalls().pipe(
      map(calls => calls.filter(call => call.status === 'completed').length),
      distinctUntilChanged(),
      filter(finished => finished > 0),
      throttleTime(TOOL_REFRESH_MS, asyncScheduler, { leading: true, trailing: true }));
    this.triggers.add(merge(finishedCalls, this.chatService.getStreamingComplete())
      .subscribe(() => this.refresh()));
    document.addEventListener('visibilitychange', this.onVisibilityChange);
    this.refresh();
  }

  ngOnChanges(changes: SimpleChanges): void {
    const moved = [changes['sessionId'], changes['workingDirectory']]
      .some(change => change && !change.firstChange);
    if (moved) {
      this.reset();
      this.refresh();
    } else if (changes['active'] && !changes['active'].firstChange) {
      if (this.active) this.refresh();
      else this.clearTimer();
    }
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.triggers.unsubscribe();
    this.inFlight?.unsubscribe();
    this.clearTimer();
    document.removeEventListener('visibilitychange', this.onVisibilityChange);
  }

  onToggle(event: Event): void {
    const open = (event.target as HTMLDetailsElement).open;
    if (open === this.open) return;
    this.open = open;
    writeOpen(open);
    if (open) this.refresh();
    else this.clearTimer();
  }

  /** Reads the session's rows now, or once the read in flight settles. */
  private refresh(): void {
    if (!this.canRead()) return;
    if (this.inFlight) {
      this.rerun = true;
      return;
    }
    this.clearTimer();
    this.loading = true;
    this.cdr.markForCheck();
    const read = this.chatService.getSessionInsights(this.sessionId, this.workingDirectory).subscribe({
      next: snapshot => this.apply(snapshot),
      error: (error: unknown) => {
        this.status = failure(error);
        this.settle();
      },
      complete: () => this.settle()
    });
    // An answer that came synchronously has already settled.
    if (!read.closed) this.inFlight = read;
  }

  private apply(snapshot: SessionInsightsSnapshot | null): void {
    if (!snapshot || snapshot.available === false) {
      this.rows = [];
      this.live = false;
      this.status = snapshot?.status || 'Session insights are unavailable';
    } else {
      this.rows = (snapshot.lines ?? []).map(toRow);
      this.live = snapshot.live === true;
      this.status = this.rows.length ? undefined : 'No insights yet';
    }
    this.updatedAt = new Date();
  }

  private settle(): void {
    this.inFlight = undefined;
    this.loading = false;
    if (this.rerun) {
      this.rerun = false;
      this.refresh();
    }
    if (!this.inFlight) this.schedule();
    this.render();
  }

  private schedule(): void {
    this.clearTimer();
    if (!this.canRead()) return;
    this.timer = setTimeout(() => {
      this.timer = undefined;
      this.refresh();
    }, this.live ? LIVE_REFRESH_MS : IDLE_REFRESH_MS);
  }

  private canRead(): boolean {
    return this.open && this.active && !!this.sessionId && !this.destroyed
      && document.visibilityState !== 'hidden';
  }

  /** Forgets the previous session's rows and drops its read. */
  private reset(): void {
    this.inFlight?.unsubscribe();
    this.inFlight = undefined;
    this.rerun = false;
    this.clearTimer();
    this.loading = false;
    this.live = false;
    this.rows = [];
    this.status = undefined;
    this.updatedAt = undefined;
    this.cdr.markForCheck();
  }

  private clearTimer(): void {
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
  }

  /** The chat pane detaches its change detection while a reply streams, so answers render here. */
  private render(): void {
    if (!this.destroyed) this.cdr.detectChanges();
  }
}

function toRow(line: string): InsightRow {
  if (line.startsWith('↳')) return { text: line, detail: true };
  const topic = TOPIC.exec(line);
  return topic ? { topic: topic[1], text: topic[2], detail: false } : { text: line, detail: false };
}

/** Why a read failed, in the drawer's words; the rows it already shows stay. */
function failure(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) return 'Session insights could not be read';
  if (error.status === 0) return 'Session insights could not be read: the chat server is unreachable';
  const message = error.error?.message;
  return typeof message === 'string' && message
    ? `Session insights are unavailable: ${message}`
    : `Session insights are unavailable (HTTP ${error.status})`;
}

function readOpen(): boolean {
  try {
    return localStorage.getItem(INSIGHTS_DRAWER_OPEN_KEY) === 'true';
  } catch {
    return false;
  }
}

function writeOpen(open: boolean): void {
  try {
    localStorage.setItem(INSIGHTS_DRAWER_OPEN_KEY, String(open));
  } catch {
    // Without storage the drawer simply starts closed next time.
  }
}
