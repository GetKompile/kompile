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
import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { Subscription } from 'rxjs';
import { ToolCallChartComponent } from '@shared/components/tool-call-chart/tool-call-chart.component';
import {
  InsightsSettings, InsightsSettingsView, InsightsTopicReport, LocalAgentChatService
} from '@shared/services/local-agent-chat.service';

/** One tab: an insights topic, what its report covers, and a question it answers. */
export interface InsightsTopic {
  id: string;
  label: string;
  about: string;
  example: string;
}

/** The topics the CLI's insights tool answers, in the order the tool lists them. */
export const INSIGHTS_TOPICS: readonly InsightsTopic[] = [
  { id: 'overview', label: 'Overview', about: 'Every topic\'s headline, side by side.', example: 'last 30 days' },
  { id: 'judge', label: 'Judge',
    about: 'Judge verdicts: flags, stops, blocked turns, overrides and the most-flagged tools.',
    example: 'flags for bash' },
  { id: 'tools', label: 'Tools',
    about: 'Tool calls: counts, errors and p50/p95 latency per tool, the slowest calls and the latest errors.',
    example: 'slowest calls of the read tool' },
  { id: 'tests', label: 'Tests',
    about: 'Test milestones: pass rate and latest result per module, failing test classes and known regressions.',
    example: 'the core tests' },
  { id: 'crawl', label: 'Crawls',
    about: 'Crawl jobs with their progress, counts and errors, and the project\'s knowledge bases.',
    example: 'for docs' },
  { id: 'graph', label: 'Graphs',
    about: 'The project\'s knowledge graphs and the server\'s fact sheets, their predicates and entity types, '
      + 'and the relations around a node.',
    example: 'around Alice' }
];

/** The windows the page offers; a question can name any other ("last 3 days", "this month"). */
export const INSIGHTS_WINDOWS: readonly { value: string; label: string }[] = [
  { value: '', label: 'Topic default' },
  { value: 'today', label: 'Today' },
  { value: 'last 24 hours', label: 'Last 24 hours' },
  { value: 'last 7 days', label: 'Last 7 days' },
  { value: 'last 30 days', label: 'Last 30 days' },
  { value: 'all time', label: 'All time' }
];

/** How often the shown report is read again while auto-refresh is on and the page is visible. */
export const AUTO_REFRESH_MS = 30_000;

/** What a tab shows: its question, and the report last read for it. */
interface TopicState {
  question: string;
  report?: InsightsTopicReport;
  /** The report's text under its headline, as the terminal prints it. */
  text?: string;
  /** The read the report answers: topic, question and directory. */
  reportKey?: string;
  /** The read in flight, or the last one when it answered; a failed read leaves none. */
  readKey?: string;
  error?: string;
  loading: boolean;
  readAt?: Date;
  inFlight?: Subscription;
}

/** One insights.json limit as the form edits it. */
interface SettingField {
  key: Exclude<keyof InsightsSettings, 'sessionPanel'>;
  label: string;
  hint: string;
  /** Edited in MiB, saved in bytes. */
  mebibytes?: boolean;
  /** int fields stop at 2^31 - 1; byte counts are longs. */
  max: number;
}

const MIB = 1024 * 1024;
const MAX_INT = 2_147_483_647;

/** The limits the form edits, in the order insights.json lists them. */
export const INSIGHTS_SETTING_FIELDS: readonly SettingField[] = [
  { key: 'defaultWindowDays', label: 'Default window (days)', hint: 'The window when a question names none',
    max: MAX_INT },
  { key: 'maxRows', label: 'Table rows', hint: 'Rows in a ranked table', max: MAX_INT },
  { key: 'maxExamples', label: 'Examples', hint: 'Recent examples under a table, such as the newest judge flags',
    max: MAX_INT },
  { key: 'maxSessions', label: 'Judge sessions', hint: 'Judge session logs read per report, newest first',
    max: MAX_INT },
  { key: 'maxBytesPerFile', label: 'Judge log tail (MiB)', hint: 'Read from the end of each judge session log',
    mebibytes: true, max: Number.MAX_SAFE_INTEGER },
  { key: 'maxToolIndexBytes', label: 'Tool-call index tail (MiB)',
    hint: 'Read from the end of the tool-call index; 256 MiB holds about a month of calls',
    mebibytes: true, max: Number.MAX_SAFE_INTEGER },
  { key: 'sparklineBuckets', label: 'Chart points', hint: 'Points in a sparkline and in a chart series',
    max: MAX_INT }
];

type SettingsForm = Record<SettingField['key'], string> & { sessionPanel: boolean };

/**
 * The chat app's insights page: one tab per topic of the CLI's insights tool — judge verdicts,
 * tool calls, test milestones, crawls and knowledge graphs — each read across the project's chat
 * sessions, with a question that narrows it and a window. A report is drawn as the tool's chart
 * above the text the terminal prints. Reads happen when a tab is opened or asked, on Refresh, and
 * every 30 seconds while auto-refresh is on and the page is visible; the topic, question, window
 * and directory stay in the URL, so a report can be bookmarked or opened from a chat.
 */
@Component({
  selector: 'app-insights-page',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule,
    MatProgressBarModule, MatSelectModule, MatTabsModule, ToolCallChartComponent
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './insights-page.component.html',
  styleUrls: ['./insights-page.component.css']
})
export class InsightsPageComponent implements OnInit, OnDestroy {
  readonly topics = INSIGHTS_TOPICS;
  readonly windows = INSIGHTS_WINDOWS;
  readonly settingFields = INSIGHTS_SETTING_FIELDS;

  activeId = INSIGHTS_TOPICS[0].id;
  window = '';
  workingDirectory = '';
  autoRefresh = false;
  readonly states: Record<string, TopicState> = Object.fromEntries(
    INSIGHTS_TOPICS.map(topic => [topic.id, { question: '', loading: false } as TopicState]));

  settingsView?: InsightsSettingsView;
  settingsForm?: SettingsForm;
  /** The form as loaded or last saved, so only fields the user changed are written. */
  private settingsLoaded?: SettingsForm;
  settingsLoading = false;
  settingsSaving = false;
  settingsError?: string;
  settingsStatus?: string;

  private readonly subscriptions = new Subscription();
  private settingsRead?: Subscription;
  private timer?: ReturnType<typeof setTimeout>;
  private destroyed = false;
  private readonly onVisibilityChange = () => {
    if (document.visibilityState === 'hidden') this.clearTimer();
    else if (this.autoRefresh) this.refresh();
  };

  constructor(
    private readonly chatService: LocalAgentChatService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly cdr: ChangeDetectorRef
  ) {}

  get activeTopic(): InsightsTopic {
    return INSIGHTS_TOPICS.find(topic => topic.id === this.activeId) ?? INSIGHTS_TOPICS[0];
  }

  get state(): TopicState {
    return this.states[this.activeId];
  }

  ngOnInit(): void {
    this.subscriptions.add(this.route.queryParamMap.subscribe(params => {
      this.applyParams(params);
      this.show(false);
    }));
    document.addEventListener('visibilitychange', this.onVisibilityChange);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.subscriptions.unsubscribe();
    Object.values(this.states).forEach(state => state.inFlight?.unsubscribe());
    this.settingsRead?.unsubscribe();
    this.clearTimer();
    document.removeEventListener('visibilitychange', this.onVisibilityChange);
  }

  /**
   * Opens a topic's tab, reading its report unless the one it shows answers the same read. The
   * read starts before the URL changes, so the query-parameter echo finds it already under way.
   */
  select(id: string): void {
    if (!this.states[id] || id === this.activeId) return;
    this.activeId = id;
    this.show(false);
    this.writeParams();
  }

  /** Reads the open tab's report for its question, even when it already shows that read. */
  ask(): void {
    this.show(true);
    this.writeParams();
  }

  onWindowChange(): void {
    this.ask();
  }

  refresh(): void {
    this.show(true);
  }

  onAutoRefreshChange(): void {
    if (this.autoRefresh) this.refresh();
    else this.clearTimer();
  }

  /** The settings disclosure loads insights.json the first time it opens. */
  onSettingsToggle(event: Event): void {
    if ((event.target as HTMLDetailsElement).open && !this.settingsView && !this.settingsLoading) {
      this.loadSettings();
    }
  }

  loadSettings(): void {
    this.settingsRead?.unsubscribe();
    this.settingsLoading = true;
    this.settingsError = undefined;
    this.settingsStatus = undefined;
    this.cdr.markForCheck();
    this.settingsRead = this.chatService.getInsightsSettings().subscribe({
      next: view => {
        this.applySettings(view);
        this.settingsLoading = false;
        this.render();
      },
      error: (error: unknown) => {
        this.settingsError = failure(error, 'Insights settings could not be read');
        this.settingsLoading = false;
        this.render();
      }
    });
  }

  /** Fills the form with the defaults; nothing is written until Save. */
  useDefaults(): void {
    if (!this.settingsView) return;
    this.settingsForm = toForm(this.settingsView.defaults);
    this.settingsStatus = 'Defaults filled in; Save writes the ones that differ.';
    this.settingsError = undefined;
  }

  /** Writes the fields the user changed to insights.json, then reads every tab again. */
  saveSettings(): void {
    if (!this.settingsForm || !this.settingsLoaded || this.settingsSaving) return;
    const changes: Partial<InsightsSettings> = {};
    for (const field of INSIGHTS_SETTING_FIELDS) {
      const shown = this.settingsForm[field.key].trim();
      if (shown === this.settingsLoaded[field.key]) continue;
      const value = parseSetting(field, shown);
      if (typeof value === 'string') {
        this.settingsError = value;
        this.settingsStatus = undefined;
        return;
      }
      changes[field.key] = value;
    }
    if (this.settingsForm.sessionPanel !== this.settingsLoaded.sessionPanel) {
      changes.sessionPanel = this.settingsForm.sessionPanel;
    }
    if (Object.keys(changes).length === 0) {
      this.settingsError = undefined;
      this.settingsStatus = 'Nothing changed.';
      return;
    }
    this.settingsSaving = true;
    this.settingsError = undefined;
    this.settingsStatus = undefined;
    this.cdr.markForCheck();
    this.subscriptions.add(this.chatService.saveInsightsSettings(changes).subscribe({
      next: view => {
        this.applySettings(view);
        this.settingsSaving = false;
        this.settingsStatus = `Saved to ${view.file}.`;
        // Every report keeps to these limits, so the ones already shown are stale.
        Object.values(this.states).forEach(state => state.readKey = undefined);
        this.show(true);
        this.render();
      },
      error: (error: unknown) => {
        this.settingsError = failure(error, 'Insights settings could not be saved');
        this.settingsSaving = false;
        this.render();
      }
    }));
  }

  private applySettings(view: InsightsSettingsView): void {
    this.settingsView = view;
    this.settingsForm = toForm(view.settings);
    this.settingsLoaded = { ...this.settingsForm };
  }

  private applyParams(params: ParamMap): void {
    const topic = params.get('topic');
    if (topic && this.states[topic]) this.activeId = topic;
    const question = params.get('question');
    if (question !== null) this.state.question = question;
    const window = params.get('window');
    if (window !== null && INSIGHTS_WINDOWS.some(option => option.value === window)) this.window = window;
    const directory = params.get('workingDirectory');
    if (directory !== null) this.workingDirectory = directory;
  }

  /** Keeps the URL on what the page shows; replacing the entry keeps Back for leaving the page. */
  private writeParams(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {
        topic: this.activeId,
        question: this.state.question.trim() || null,
        window: this.window || null,
        workingDirectory: this.workingDirectory.trim() || null
      },
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

  /**
   * Reads the open tab. Unless forced, a tab whose report answers this read, or is being read
   * for it, keeps it; a read for another question first clears the old answer away.
   */
  private show(force: boolean): void {
    if (this.destroyed) return;
    const topic = this.activeId;
    const state = this.states[topic];
    const question = [state.question.trim(), this.window].filter(Boolean).join(', ');
    const directory = this.workingDirectory.trim();
    const key = JSON.stringify([topic, question, directory]);
    if (!force && state.readKey === key) {
      this.schedule();
      return;
    }
    this.clearTimer();
    state.inFlight?.unsubscribe();
    if (state.reportKey !== key) {
      state.report = undefined;
      state.text = undefined;
      state.reportKey = undefined;
      state.readAt = undefined;
    }
    state.readKey = key;
    state.loading = true;
    state.error = undefined;
    this.cdr.markForCheck();
    const read = this.chatService.getInsightsReport(topic, question || undefined, directory || undefined)
      .subscribe({
        next: report => {
          state.report = report;
          state.text = reportText(report);
          state.reportKey = key;
          state.readAt = new Date();
        },
        error: (error: unknown) => {
          state.error = failure(error, 'This report could not be read');
          // A failed read is not an answer: opening the tab again reads again.
          state.readKey = undefined;
          this.settle(topic, state);
        },
        complete: () => this.settle(topic, state)
      });
    state.inFlight = read.closed ? undefined : read;
  }

  private settle(topic: string, state: TopicState): void {
    state.inFlight = undefined;
    state.loading = false;
    if (topic === this.activeId) this.schedule();
    this.render();
  }

  private schedule(): void {
    this.clearTimer();
    if (!this.autoRefresh || this.destroyed || this.state.inFlight || document.visibilityState === 'hidden') return;
    this.timer = setTimeout(() => {
      this.timer = undefined;
      this.refresh();
    }, AUTO_REFRESH_MS);
  }

  private clearTimer(): void {
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
  }

  private render(): void {
    if (!this.destroyed) this.cdr.markForCheck();
  }
}

/**
 * The report's text without its first line when that line is the headline the page shows above
 * it, and without the blank lines that follow; the table's own indentation stays.
 */
function reportText(report: InsightsTopicReport): string {
  const text = (report.text ?? '').replace(/\s+$/, '');
  const headline = report.headline?.trim();
  if (!headline || !text.startsWith(headline)) return text;
  const rest = text.slice(headline.length);
  return rest === '' || /^[ \t]*\n/.test(rest) ? rest.replace(/^[ \t]*\n(?:[ \t]*\n)*/, '') : text;
}

function toForm(settings: InsightsSettings): SettingsForm {
  const form = { sessionPanel: settings.sessionPanel } as SettingsForm;
  for (const field of INSIGHTS_SETTING_FIELDS) {
    const value = settings[field.key];
    // Six significant digits: a whole MiB reads as one, and a small byte count does not read as 0.
    form[field.key] = field.mebibytes ? String(Number((value / MIB).toPrecision(6))) : String(value);
  }
  return form;
}

/** The value insights.json takes for the field, or why the shown one cannot be saved. */
function parseSetting(field: SettingField, shown: string): number | string {
  const number = shown === '' ? NaN : Number(shown);
  const value = field.mebibytes ? Math.round(number * MIB) : number;
  if (!Number.isFinite(number) || !Number.isInteger(value) || value <= 0 || value > field.max) {
    return field.mebibytes
      ? `${field.label} must be a positive number of MiB`
      : `${field.label} must be a positive whole number`;
  }
  return value;
}

/** Why a read failed, in the page's words. */
function failure(error: unknown, prefix: string): string {
  if (!(error instanceof HttpErrorResponse)) return prefix;
  if (error.status === 0) return `${prefix}: the chat server is unreachable`;
  const message = error.error?.message;
  if (typeof message === 'string' && message) return `${prefix}: ${message}`;
  if (error.status === 503) return `${prefix}: the Kompile CLI harness is unavailable`;
  return `${prefix} (HTTP ${error.status})`;
}
