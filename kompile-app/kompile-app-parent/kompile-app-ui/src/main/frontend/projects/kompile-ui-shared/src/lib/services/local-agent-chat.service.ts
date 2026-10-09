/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable, NgZone } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { Observable, Subject, BehaviorSubject } from 'rxjs';
import { throttleTime } from 'rxjs/operators';
import { BaseService } from './base.service';
import { ChatStorageService } from './chat-storage.service';
import { SKIP_ERROR_SNACKBAR } from './http-error.interceptor';
import { ReasoningTrailDto } from './kb-grounding.service';
import {
  AgentProvider,
  LocalAgentSession,
  LocalAgentMessage,
  CommandEventData,
  CommandOutcome,
  LocalAgentChatRequest,
  ChatTabState,
  ToolUseEvent,
  ResultEvent,
  RetrievedSource,
  createUserMessage,
  createAssistantMessage,
  createNewSession,
  ChatHistoryEntry,
  MessageAttachment,
  ToolCallChart
} from '../models/api-models';

/**
 * Aggregated session-configuration snapshot from GET /agents/chat/session-config.
 * Per-section keys each carry the same payload shape the dialog renders for the
 * single-command menus ({@code menu:"model"}, {@code menu:"role"}, …).
 */
export interface SessionConfigSnapshot {
  menu: 'config';
  available?: boolean;
  status?: string;
  sessionId?: string;
  model?: CommandEventData;
  thinking?: CommandEventData;
  role?: CommandEventData;
  fast?: CommandEventData;
  ultracode?: CommandEventData;
  reminders?: CommandEventData;
  remindersGlobal?: CommandEventData;
  loops?: CommandEventData;
  loopsGlobal?: CommandEventData;
  queue?: CommandEventData;
  continue?: CommandEventData;
  judge?: CommandEventData;
}

/**
 * The session's insight rows from GET /agents/chat/session-insights: the rows the terminal's
 * dashboard area shows (judge flags, tool counts and latency, the last test milestone, crawl
 * progress). A summary row reads "Topic: …"; a row starting with "↳ " details the row above it.
 * {@code live} is true while a crawl runs. {@code available:false} carries a {@code status}.
 */
export interface SessionInsightsSnapshot {
  menu: 'insights';
  available?: boolean;
  status?: string;
  sessionId?: string;
  schemaVersion?: string;
  title?: string;
  contextVersion?: number;
  lines?: string[];
  live?: boolean;
}

/**
 * One topic's report from GET /agents/chat/insights, over every chat session: what the CLI's
 * insights tool answers for judge verdicts, tool calls, test milestones, crawls, graphs, or the
 * overview of them all. {@code available:false} carries a {@code status} instead of a report.
 */
export interface InsightsTopicReport {
  menu: 'insights';
  topic: string;
  available: boolean;
  status?: string;
  headline?: string;
  /** The report as the terminal prints it: tables and sparklines in a monospace font. */
  text?: string;
  chart?: ToolCallChart;
  /** Local tool measurements, never combined with the provider/model execution ledger. */
  usage?: ToolUsageReport;
}

export interface ToolTokenMeasurement {
  tokens?: number | null;
  status: string;
  representation?: string;
  method?: string;
  tokenizerId?: string;
  tokenizerVersion?: string;
  detail?: string;
}

export interface ToolUsageTotals {
  calls: number;
  argumentsTokens?: number | null;
  /** Sum of FULLY_MEASURED payloads only; partial/unknown calls are not zero-token calls. */
  payloadTokens?: number | null;
  unmeasuredPayloadCalls: number;
  partialPayloadCalls: number;
  degradedCalls?: number;
}

export interface InvocationContentPage {
  available: boolean;
  status?: string;
  text?: string;
  offset: number;
  nextOffset?: number;
  hasMore?: boolean;
  sizeBytes?: number;
}

export interface ToolSessionDetails {
  sessionId: string;
  title?: string;
  metricsStatus?: string;
  sessionMetrics?: {
    tokens?: { input?: number; output?: number; total?: number; cacheRead?: number; cacheCreation?: number;
      estimatedInput?: number; estimatedOutput?: number; estimatedTotal?: number };
    agentic?: { compactions?: number; compactionTokensBefore?: number; compactionTokensAfter?: number; tokensSavedByCompaction?: number };
    [key: string]: unknown;
  };
}

export interface ToolUsageCall {
  invocationId: string;
  sessionId: string;
  title?: string;
  detail?: {
    available: boolean;
    status?: string;
    context?: Record<string, unknown>;
    catalog?: Record<string, unknown>;
    arguments?: InvocationContentPage;
    output?: InvocationContentPage;
    rawOutput?: InvocationContentPage;
    structured?: InvocationContentPage;
  };
  tool: string;
  requestedToolName?: string;
  resolvedToolName?: string;
  startedEpochMs?: number;
  finishedEpochMs?: number;
  durationMs?: number;
  outcome?: string;
  disposition?: string;
  errorResponse?: boolean;
  arguments?: ToolTokenMeasurement;
  payload?: ToolTokenMeasurement;
  rawPayload?: ToolTokenMeasurement;
  modelExecutions?: Record<string, unknown>[];
  accountingDegraded?: boolean;
  accountingNote?: string;
}

export interface ToolUsageReport {
  summary: ToolUsageTotals;
  perTool: (ToolUsageTotals & { tool: string; trend?: (number | null)[] })[];
  perSession: (ToolUsageTotals & ToolSessionDetails)[];
  selectedSession?: ToolSessionDetails;
  /** Historical catalog records are not part of the measured token ledger. */
  catalog?: { calls: { id: string; sessionId: string; toolName: string; toolInput?: string; summary?: string;
    timestamp?: string | number; source?: string; agentName?: string; durationMs?: number; isError?: boolean;
    detail?: ToolUsageCall['detail']; [key: string]: unknown }[]; hasMore?: boolean; truncated?: boolean; status?: string };
  calls: ToolUsageCall[];
  offset: number;
  limit: number;
  totalCalls: number;
  hasMore: boolean;
  labels?: string[];
  truncated?: boolean;
  malformedLines?: number;
  modelExecutions?: { inputTokens?: number; outputTokens?: number; [key: string]: unknown };
  measurementBuckets?: Record<string, unknown>;
}

/** The limits every insights report keeps to, as insights.json holds them. */
export interface InsightsSettings {
  defaultWindowDays: number;
  maxRows: number;
  maxExamples: number;
  maxSessions: number;
  maxBytesPerFile: number;
  maxToolIndexBytes: number;
  sparklineBuckets: number;
  sessionPanel: boolean;
}

/**
 * insights.json from GET and PUT /agents/chat/insights/config: the file, its settings, the
 * defaults, and a {@code warning} when the file could not be used and the defaults apply.
 */
export interface InsightsSettingsView {
  file: string;
  settings: InsightsSettings;
  defaults: InsightsSettings;
  warning?: string;
}

/** One selectable vendor chip of the model section. */
export interface CommandVendorEntry {
  /** Canonical vendor key — matches the interactive picker's vendor page. */
  vendor: string;
  /** Optional friendly name when it differs from the vendor key. */
  display?: string;
  /** True for the vendor this session currently uses. */
  current?: boolean;
}

/**
 * Token metrics from LLM streaming responses.
 */
export interface HarnessActivityEntry {
  id: string;
  description: string;
  state: string;
  command?: string;
  output?: string;
  /** Process kind label: command, judge, enforcer, mcp, or shared (published by another session). */
  kind?: string;
  /** False when this run can read the process but not stop it (a shared process). */
  killable?: boolean;
  /** Owning session of a shared process. */
  owner?: string;
  /** Process details, as the CLI's process panel and /process-status show them. */
  pid?: number;
  /** ISO-8601 instants; the duration is as of the snapshot. */
  startedAt?: string;
  endedAt?: string;
  durationMs?: number;
  exitCode?: number;
  /** The process log on the harness host. */
  logFile?: string;
  /** Further process metadata, e.g. a note on how a process from an earlier run ended. */
  details?: Record<string, string>;
  /** An armed completion monitor: the agent wakes when this process exits. */
  monitor?: { message: string; armedAt: string };
}
export interface HarnessSubagent extends HarnessActivityEntry {
  type: string;
  running: boolean;
  canSend: boolean;
  canCancel: boolean;
}
export interface HarnessActivity {
  backgroundable: boolean;
  turnActive: boolean;
  /** False once the run takes no more live input: it is finishing and only its end is still to come. */
  controlsOpen?: boolean;
  processes: HarnessActivityEntry[];
  tasks: HarnessActivityEntry[];
  subagents?: HarnessSubagent[];
}
/** The run stopped taking live input before it took this control; nothing of it ran. */
export class HarnessRunClosedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'HarnessRunClosedError';
  }
}
export type HarnessControlAction = 'background' | 'process_list' | 'process_output' | 'process_kill' | 'process_unmonitor' | 'input' | 'command' | 'subagent_input' | 'subagent_cancel' | 'workflow_approve';
export interface HarnessReconnectBookmark {
  runId: string;
  browserSessionId: string;
  backendUrl: string;
  expiresAt: number;
  seed: LocalAgentSession;
  messageStart: number;
  cursor?: number;
  turnId?: number;
  currentMessageId?: string;
  lastMessageId?: string;
  agent: AgentProvider;
}
export interface HarnessControlReply {
  requestId: string;
  action: HarnessControlAction;
  ok: boolean;
  message: string;
  targetId?: string;
  output?: string;
  /** command: a terminal-session built-in; it runs when the live run finishes. */
  deferred?: boolean;
  /** command: resolved to model input and queued for the next turn boundary. */
  queued?: boolean;
  /** workflow_approve: the gate approved, and every gate of the team approved so far. */
  gate?: string;
  approved?: string[];
}

/** A participant of a session's workflow team; its model appears by label only. */
export interface WorkflowTeamParticipant {
  id: string;
  role?: string;
  model?: string;
  capabilities?: string[];
  /** Present when the participant may delegate; empty means to nobody. */
  delegatesTo?: string[];
}
/** The workflow team a session was started with, as its harness session event reports it. */
export interface WorkflowTeam {
  name: string;
  version?: number;
  lead: string;
  /** Lead first. */
  participants: WorkflowTeamParticipant[];
  routing?: Record<string, string>;
  gates: { implementationRequires?: string; completionRequires?: string; approved: string[] };
  maxConcurrentWorkers?: number;
}
/** The harness's answer to a gate approval, whichever way it was delivered. */
export interface WorkflowApprovalOutcome {
  ok: boolean;
  message: string;
}

export interface TokenMetrics {
  outputTokens: number;
  inputTokens: number;
  totalGenerationMs: number;
  tokensPerSecond: number;
  model?: string;
}

/**
 * Chat statistics from agent response.
 */
export interface ChatStats {
  durationMs: number;
  costUsd: number;
  numTurns: number;
  isError: boolean;
  tokenMetrics?: TokenMetrics;
}

/**
 * Context budget for an agent's lane, from GET /agents/chat/context-budget.
 * The window comes from the authoritative source for what the chat talks to:
 * staging metadata for local models, the model catalogs otherwise.
 */
export interface ContextBudget {
  agentName: string;
  model: string;
  contextWindow: number;
  maxOutputTokens: number;
  inputBudgetTokens: number;
  source: string;
  compactTriggerRatio: number;
}

/**
 * Server-side compaction notice streamed as a `compaction` SSE event when the
 * backend auto-compacted the history it was sent.
 */
export interface CompactionEvent {
  tokensBefore: number;
  tokensAfter: number;
  contextWindow: number;
  model: string;
  summary?: string;
  usedFallback?: boolean;
}

/**
 * Result of POST /agents/chat/compact (manual compaction).
 */
export interface CompactChatResponse {
  compacted: boolean;
  tokensBefore?: number;
  tokensAfter?: number;
  contextWindow?: number;
  model?: string;
  summary?: string;
  usedFallback?: boolean;
  compactedHistory?: ChatHistoryEntry[];
  reason?: string;
  error?: string;
}

/** The server can no longer replay the run (finished long ago, or restarted): its transcript is the record. */
class ReplayGoneError extends Error {
  constructor(readonly status: number) { super(`Run replay unavailable (${status})`); this.name = 'ReplayGoneError'; }
}

class ReplayHttpError extends Error {
  constructor(readonly status: number) {
    super(`Cannot replay run (${status}). Stop the saved run or inspect its transcript; no work was restarted.`);
    this.name = 'ReplayHttpError';
  }
}

/**
 * Service for local agent chat with streaming support.
 *
 * Features:
 * - SSE streaming for real-time responses
 * - Efficient content accumulation (array buffer)
 * - Throttled UI updates
 * - Session and message persistence
 * - Multi-tab support
 */
@Injectable({
  providedIn: 'root'
})
export class LocalAgentChatService extends BaseService {

  // Streaming state
  private streamingContentRaw$ = new Subject<{ content: string; epoch: number }>();
  private contentEpoch = 0;
  private streamingContent$ = new BehaviorSubject<string>('');
  private streamingComplete$ = new Subject<LocalAgentMessage>();
  private streamingError$ = new Subject<string>();
  private isStreaming$ = new BehaviorSubject<boolean>(false);

  // Event streams (following build-orchestrator pattern)
  private toolUse$ = new Subject<ToolUseEvent>();
  private toolCalls$ = new Subject<ToolUseEvent[]>();
  private result$ = new Subject<ResultEvent>();
  private filesModified$ = new Subject<string[]>();
  private sources$ = new Subject<RetrievedSource[]>();
  private chatStats$ = new Subject<ChatStats>();
  private sessionTitle$ = new Subject<{ sessionId: string; title: string }>();
  private compaction$ = new Subject<CompactionEvent>();

  // Array buffer for efficient string accumulation
  private contentChunks: string[] = [];
  private lastEmittedLength = 0;

  // Throttle interval for UI updates (ms)
  private readonly THROTTLE_INTERVAL = 50;

  // Current streaming message reference
  private currentStreamingMessage: LocalAgentMessage | null = null;
  private streamStartTime: number = 0;

  // AbortController for cancelling fetch requests
  private currentAbortController: AbortController | null = null;

  // Current process ID for backend cancellation
  private currentProcessId: string | null = null;

  harnessActivity: HarnessActivity | null = null;
  liveControlsReady = false;
  liveInputHistory: string[] = [];
  private liveTurnTexts: string[] = []; // compatibility with pre-turn_started harnesses
  private liveTurnId = 0;
  private lastEventId = 0;
  private reconnectable = false;
  private browserSessionId = '';
  private reconnectSeed: LocalAgentSession | null = null;
  private lastCheckpointAt = 0;
  /** Set when the live view may be missing events; the turn's end then reloads the CLI transcript. */
  private transcriptStale = false;
  private readonly transcriptReload$ = new Subject<string>();
  /** Reconnect attempts while the page is visible and online; time asleep or offline costs none. */
  private static readonly MAX_RECONNECT_ATTEMPTS = 8;
  /** Hidden at least this long, a stream is assumed dead (a phone slept) and replayed from its cursor. */
  private static readonly WAKE_RECONNECT_MS = 5000;

  /** Browser session ids whose CLI transcript now holds more than the live view showed. */
  getTranscriptReloads(): Observable<string> { return this.transcriptReload$.asObservable(); }

  getReconnectBookmark(browserSessionId: string): HarnessReconnectBookmark | null {
    const key = 'kompile-live-run:' + browserSessionId;
    // A closed tab loses sessionStorage, not the server's run. Keep old tab checkpoints readable.
    for (const kind of ['localStorage', 'sessionStorage'] as const) {
      try {
        const value = JSON.parse(window[kind].getItem(key) || 'null');
        if (value && value.backendUrl === this.backendUrl && value.expiresAt > Date.now()
          && typeof value.runId === 'string' && value.runId.startsWith('harness-')
          && Array.isArray(value.seed?.messages) && value.agent && Number.isInteger(value.messageStart)
          && value.messageStart >= 0 && value.messageStart + 2 <= value.seed.messages.length
          && (value.cursor === undefined || (Number.isSafeInteger(value.cursor) && value.cursor >= 0)))
          return value as HarnessReconnectBookmark;
      } catch { /* Try the other store if storage is unavailable or an old value is malformed. */ }
    }
    return null;
  }

  private saveReconnectBookmark(session = this.reconnectSeed): void {
    if (!this.currentProcessId || !session || !this.liveAgent) return;
    this.lastCheckpointAt = Date.now();
    const value: HarnessReconnectBookmark = { runId: this.currentProcessId, browserSessionId: this.browserSessionId,
      backendUrl: this.backendUrl, expiresAt: Date.now() + 33 * 60_000, seed: session,
      messageStart: this.liveMessageStart, agent: this.liveAgent, cursor: this.lastEventId, turnId: this.liveTurnId,
      currentMessageId: this.currentStreamingMessage?.id, lastMessageId: this.lastLiveMessage?.id };
    try {
      const json = JSON.stringify(value, (key, field) => key === 'agent' && field
        ? { name: field.name, displayName: field.displayName, agentType: field.agentType }
        : key === 'environment' ? undefined : field);
      if (json.length > 1_048_576) return;
      const key = 'kompile-live-run:' + this.browserSessionId;
      try { localStorage.setItem(key, json); }
      catch { sessionStorage.setItem(key, json); return; }
      try { sessionStorage.removeItem(key); } catch { }
    } catch { /* storage is optional; in-page replay still works */ }
  }

  private forgetReconnectBookmark(browserSessionId = this.browserSessionId): void {
    const key = 'kompile-live-run:' + browserSessionId;
    try { localStorage.removeItem(key); } catch { }
    try { sessionStorage.removeItem(key); } catch { }
  }

  private workflowTeams = new Map<string, WorkflowTeam | null>();

  /**
   * The workflow team a chat session's harness last reported, or null when it has none. Kept for
   * the tab, like the reconnect bookmark, so the team and its gates stay visible between runs.
   */
  getWorkflowTeam(browserSessionId: string | undefined): WorkflowTeam | null {
    if (!browserSessionId) return null;
    if (!this.workflowTeams.has(browserSessionId)) {
      let saved: WorkflowTeam | null = null;
      try { saved = LocalAgentChatService.workflowTeamOf(JSON.parse(sessionStorage.getItem('kompile-workflow-team:' + browserSessionId) || 'null')); }
      catch { /* storage is optional; the next run reports the team again */ }
      this.workflowTeams.set(browserSessionId, saved);
    }
    return this.workflowTeams.get(browserSessionId) ?? null;
  }

  private rememberWorkflowTeam(browserSessionId: string, team: WorkflowTeam | null): void {
    if (!browserSessionId) return;
    this.workflowTeams.set(browserSessionId, team);
    try {
      if (team) sessionStorage.setItem('kompile-workflow-team:' + browserSessionId, JSON.stringify(team));
      else sessionStorage.removeItem('kompile-workflow-team:' + browserSessionId);
    } catch { /* storage is optional; the next run reports the team again */ }
  }

  /** The team in a session event's workflow field, or null when the session has none. */
  private static workflowTeamOf(value: unknown): WorkflowTeam | null {
    const team = value as Partial<WorkflowTeam> | null;
    return team && typeof team === 'object' && typeof team.name === 'string' && typeof team.lead === 'string'
      && Array.isArray(team.participants) && team.participants.every(participant => typeof participant?.id === 'string')
      && Array.isArray(team.gates?.approved) ? team as WorkflowTeam : null;
  }

  async stopReconnectRun(browserSessionId: string): Promise<void> {
    const saved = this.getReconnectBookmark(browserSessionId);
    if (!saved) return;
    const response = await fetch(`${this.backendUrl}/agents/chat/cancel/${encodeURIComponent(saved.runId)}`, { method: 'POST' });
    if (!response.ok) throw new Error('Could not stop the saved run');
    this.forgetReconnectBookmark(browserSessionId);
  }

  async resumeRun(browserSessionId: string): Promise<void> {
    if (this.currentAbortController) throw new Error('A run is already connected');
    const saved = this.getReconnectBookmark(browserSessionId);
    if (!saved) throw new Error('No reconnectable run saved in this tab');
    const session = saved.seed;
    this.browserSessionId = browserSessionId;
    this.reconnectSeed = session;
    this.liveAgent = saved.agent;
    this.liveMessageStart = saved.messageStart;
    this.liveTurnId = saved.turnId || 0; this.liveTurnTexts = [];
    this.lastLiveMessage = session.messages.find(message => message.id === saved.lastMessageId) || null;
    this.lastEventId = saved.cursor || 0; this.reconnectable = true;
    this.currentProcessId = saved.runId;
    this.currentStreamingMessage = saved.cursor !== undefined
      ? session.messages.find(message => message.id === saved.currentMessageId) || null
      : session.messages[session.messages.length - 1];
    this.resetContentBuffer();
    this.accumulateContent(this.currentStreamingMessage?.content || '');
    this.streamingContent$.next(this.getCurrentContent());
    this.currentAbortController = new AbortController(); this.isStreaming$.next(true);
    const owner = this.currentAbortController;
    // The page was reloaded or away: the saved seed is a snapshot, the transcript is the record.
    this.transcriptStale = true;
    try {
      this.publishLiveMessages(session);
      await this.consumeWithReconnect(session, null);
    } catch (error) {
      if (owner.signal.aborted || this.currentAbortController !== owner) return;
      if (!(error instanceof DOMException && error.name === 'AbortError'))
        this.handleStreamError(session, error instanceof Error ? error.message : 'Reconnect failed');
    }
  }

  private async fetchReplay(runId: string): Promise<Response> {
    const response = await fetch(`${this.backendUrl}/agents/chat/events/${encodeURIComponent(runId)}?after=${this.lastEventId}`, {
      headers: { Accept: 'text/event-stream' }, signal: this.currentAbortController?.signal
    });
    // 410: replay expired or the server restarted. 400/404: this bookmark names nothing replayable.
    if (response.status === 410 || response.status === 404 || response.status === 400) throw new ReplayGoneError(response.status);
    if (!response.ok) throw new ReplayHttpError(response.status);
    return response;
  }

  /** Transport failures worth another attempt: a dropped socket, or a proxy answering for a waking server. */
  private static isNetworkFailure(error: unknown): boolean {
    return error instanceof TypeError || (error instanceof DOMException && error.name === 'NetworkError')
      || (error instanceof ReplayHttpError && (error.status === 502 || error.status === 503 || error.status === 504));
  }

  /** Resolves once the page is visible and online, or the run was abandoned: retries never burn while asleep. */
  private waitUntilReachable(owner: AbortController | null): Promise<void> {
    const reachable = () => typeof document === 'undefined'
      || (document.visibilityState !== 'hidden' && navigator.onLine !== false);
    if (reachable() || owner?.signal.aborted) return Promise.resolve();
    return new Promise(resolve => {
      const check = () => {
        if (!reachable() && !owner?.signal.aborted) return;
        document.removeEventListener('visibilitychange', check);
        window.removeEventListener('online', check);
        owner?.signal.removeEventListener('abort', check);
        resolve();
      };
      document.addEventListener('visibilitychange', check);
      window.addEventListener('online', check);
      owner?.signal.addEventListener('abort', check);
    });
  }

  /** A null response connects from the saved cursor first (a resumed run). */
  private async consumeWithReconnect(session: LocalAgentSession, initial: Response | null): Promise<void> {
    let response = initial;
    const owner = this.currentAbortController;
    const owned = () => !owner?.signal.aborted && this.currentAbortController === owner;
    for (let attempt = 0; ; ) {
      if (response) {
        const cursor = this.lastEventId;
        try {
          if (await this.readSseStream(session, response)) { this.reloadTranscriptIfStale(); return; }
          if (this.lastEventId > cursor) attempt = 0; // a stream that made progress earns a fresh budget
        } catch (error) {
          if (!owned()) return;
          if (!this.reconnectable || !LocalAgentChatService.isNetworkFailure(error)) throw error;
        }
      }
      if (!owned()) return;
      if (!this.reconnectable || !this.currentProcessId || attempt >= LocalAgentChatService.MAX_RECONNECT_ATTEMPTS)
        throw new Error('Connection lost. Reconnect the saved run; it was not restarted.');
      await this.waitUntilReachable(owner);
      if (attempt > 0) await new Promise(resolve => setTimeout(resolve, Math.min(500 * 2 ** (attempt - 1), 8000)));
      if (!owned()) return;
      attempt++;
      try { response = await this.fetchReplay(this.currentProcessId); }
      catch (error) {
        if (!owned()) return;
        if (error instanceof ReplayGoneError) { this.finishFromTranscript(session); return; }
        if (!LocalAgentChatService.isNetworkFailure(error)) throw error;
        response = null;
      }
    }
  }

  /** The run's events are gone: end the live view without an error and let the transcript fill it in. */
  private finishFromTranscript(session: LocalAgentSession): void {
    this.forgetReconnectBookmark();
    this.transcriptStale = true;
    if (this.currentStreamingMessage || this.lastLiveMessage) this.handleStreamComplete(session, {});
    else {
      this.finalizeStreaming();
      this.streamingError$.next('This run ended while the page was away; its saved transcript is reloading.');
    }
    this.reloadTranscriptIfStale();
  }

  private reloadTranscriptIfStale(): void {
    if (!this.transcriptStale) return;
    this.transcriptStale = false;
    this.transcriptReload$.next(this.browserSessionId);
  }
  private liveMessageStart = 0;
  private liveAgent: AgentProvider | null = null;
  private lastLiveMessage: LocalAgentMessage | null = null;
  private liveMessages$ = new Subject<LocalAgentMessage[]>();
  getLiveMessages(): Observable<LocalAgentMessage[]> { return this.liveMessages$.asObservable(); }

  private publishLiveMessages(session: LocalAgentSession): void {
    this.storageService.updateSession(session);
    this.liveMessages$.next(session.messages.slice(this.liveMessageStart).map(message => ({ ...message })));
  }

  /**
   * Every CLI command outcome (from modal dispatches AND typed commands).
   * The command-center modal subscribes here to update its panels in place.
   */
  private readonly commandOutcomeBus$ = new Subject<CommandOutcome>();
  getCommandOutcomes(): Observable<CommandOutcome> { return this.commandOutcomeBus$.asObservable(); }

  private startLiveTurn(session: LocalAgentSession, data: { turnId: number; source: string; text: string }): void {
    if (!Number.isSafeInteger(data.turnId) || data.turnId <= this.liveTurnId || !this.liveAgent) return;
    if (data.turnId !== this.liveTurnId + 1) throw new Error('Missing live turn boundary');
    this.thinkingBuffer = ''; // thinking is per-turn chrome; never leaks across turns
    if (data.source !== 'initial') {
      const input = createUserMessage(session.id, data.text || '');
      input.id = `${this.currentProcessId}-turn-${data.turnId}-input`;
      if (data.source === 'system') input.role = 'SYSTEM';
      session.messages.push(input);
      this.currentStreamingMessage = createAssistantMessage(session.id, this.liveAgent);
      session.messages.push(this.currentStreamingMessage);
    }
    this.liveTurnId = data.turnId;
    if (this.currentStreamingMessage) this.currentStreamingMessage.id = `${this.currentProcessId}-turn-${data.turnId}-assistant`;
    this.streamStartTime = Date.now();
    this.resetContentBuffer();
    this.streamingContent$.next('');
    this.publishLiveMessages(session);
  }
  private controlSequence = 0;
  private pendingControls = new Map<string, { resolve: (reply: HarnessControlReply) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }>();

  /** Transport acceptance is not execution success: resolve only on the correlated SSE acknowledgement. */
  async sendHarnessControl(action: HarnessControlAction, targetId?: string, text?: string): Promise<HarnessControlReply> {
    const runId = this.currentProcessId;
    if (!runId || !this.liveControlsReady) throw new Error('No live harness run');
    if (this.pendingControls.size >= 8) throw new Error('Wait for pending controls');
    if (action === 'input' || action === 'command' || action === 'subagent_input') {
      // Slash text is only ever a command: never model input, never a child's follow-up.
      if (!text?.trim()) throw new Error('Nothing to send');
      if ((action === 'command') !== text.trimStart().startsWith('/'))
        throw new Error(action === 'command' ? 'A command starts with /'
          : action === 'input' ? 'Send slash commands with the command action'
          : 'Slash commands cannot be sent to a child agent');
    }
    const requestId = `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${++this.controlSequence}`;
    const reply = new Promise<HarnessControlReply>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingControls.delete(requestId);
        reject(new Error('Harness acknowledgement timed out; execution state is unknown. Check activity before retrying.'));
      }, 15000);
      this.pendingControls.set(requestId, { resolve, reject, timer });
    });
    // Attach the rejection handler immediately, including while fetch is pending.
    const delivery = fetch(`${this.backendUrl}/agents/chat/control/${encodeURIComponent(runId)}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ version: 1, requestId, action, ...(targetId ? { targetId } : {}), ...(text !== undefined ? { text } : {}) })
    }).then(async response => {
      const result = await response.json();
      // 409: the run is finishing or gone, so it never saw the control.
      if (response.status === 409) throw new HarnessRunClosedError(result.message || 'Run is no longer taking input');
      if (!response.ok || !result.accepted) throw new Error(result.message || 'Control was not accepted');
    }).catch(error => {
      const pending = this.pendingControls.get(requestId);
      if (pending) {
        clearTimeout(pending.timer);
        this.pendingControls.delete(requestId);
        pending.reject(error instanceof Error ? error : new Error('Control delivery failed'));
      }
    });
    void delivery;
    const result = await reply;
    // A command that resolved to model input waits in the same queue as typed input.
    if (text && ((action === 'input' && result.ok) || (action === 'command' && result.queued === true)))
      this.liveInputHistory.push(text);
    return result;
  }

  /**
   * Approves a gate of a chat session's workflow team, as /workflow approve does in the terminal;
   * no gate approves the one that blocks next. The session's connected run takes it through its
   * live controls; between runs a one-shot harness records it for the next run. Either way the
   * kept team then lists every gate approved so far.
   */
  async approveWorkflowGate(browserSessionId: string, gate?: string, workingDirectory?: string): Promise<WorkflowApprovalOutcome> {
    const name = gate?.trim() || undefined;
    let outcome: WorkflowApprovalOutcome;
    let approved: unknown;
    if (this.currentProcessId && this.liveControlsReady && this.browserSessionId === browserSessionId) {
      const reply = await this.sendHarnessControl('workflow_approve', undefined, name);
      outcome = { ok: reply.ok, message: reply.message };
      approved = reply.approved;
    } else {
      const response = await fetch(`${this.backendUrl}/agents/chat/workflow/approve`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: browserSessionId, ...(workingDirectory ? { workingDirectory } : {}), ...(name ? { gate: name } : {}) })
      });
      const result = await response.json().catch(() => null);
      const ok = response.ok && result?.ok === true;
      outcome = { ok, message: typeof result?.message === 'string' && result.message ? result.message
        : ok ? 'Gate approved' : `Gate approval failed (HTTP ${response.status})` };
      approved = result?.approved;
    }
    const team = this.getWorkflowTeam(browserSessionId);
    if (outcome.ok && team && Array.isArray(approved) && approved.every(entry => typeof entry === 'string'))
      this.rememberWorkflowTeam(browserSessionId, { ...team, gates: { ...team.gates, approved: [...approved] } });
    return outcome;
  }

  private closeHarnessControls(detached = false): void {
    this.liveControlsReady = false;
    const unknownState = detached ? 'UNKNOWN (view detached; work continues)' : 'UNKNOWN (run ended)';
    if (this.harnessActivity) {
      const terminalView = (entry: HarnessActivityEntry): HarnessActivityEntry =>
        ['RUNNING', 'BACKGROUNDED'].includes(entry.state) ? { ...entry, state: unknownState } : entry;
      this.harnessActivity = { ...this.harnessActivity, backgroundable: false, turnActive: false,
        processes: this.harnessActivity.processes.map(terminalView), tasks: this.harnessActivity.tasks.map(terminalView),
        subagents: this.harnessActivity.subagents?.map(child => ({ ...child,
          state: child.running ? unknownState : child.state, running: false, canSend: false, canCancel: false })) };
    }
    for (const pending of this.pendingControls.values()) {
      clearTimeout(pending.timer);
      // Detachment loses the acknowledgement, not the run: its outcome is unknown, never retry it.
      pending.reject(detached ? new Error('View detached; control outcome unknown. Work continues on the server.')
        : new HarnessRunClosedError('Run ended before it took this control'));
    }
    this.pendingControls.clear();
  }

  constructor(
    private http: HttpClient,
    private ngZone: NgZone,
    private storageService: ChatStorageService
  ) {
    super();
    this.setupThrottledStreaming();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // STREAMING SETUP
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Setup throttled streaming to prevent UI freezing.
   */
  private setupThrottledStreaming(): void {
    this.streamingContentRaw$.pipe(
      throttleTime(this.THROTTLE_INTERVAL, undefined, { leading: true, trailing: true })
    ).subscribe(({ content, epoch }) => {
      if (epoch !== this.contentEpoch) return;
      if (content.length - this.lastEmittedLength > 0) {
        this.lastEmittedLength = content.length;
        this.ngZone.run(() => {
          this.streamingContent$.next(content);
        });
      }
    });
  }

  /**
   * Accumulate content using array buffer.
   */
  private accumulateContent(chunk: string): string {
    this.contentChunks.push(chunk);
    return this.contentChunks.join('');
  }

  /**
   * Reset content buffer for new streaming session.
   */
  private resetContentBuffer(): void {
    this.contentEpoch++;
    this.contentChunks = [];
    this.lastEmittedLength = 0;
  }

  /** Accumulated model reasoning for the current turn (display-only). */
  private thinkingBuffer = '';

  /** <thinking> block renderer prefix; empty when no reasoning has streamed. */
  private renderThinkingPrefix(): string {
    if (this.thinkingBuffer.length === 0) return '';
    // While thinking is the only content the block is still open (renderer
    // shows a live cursor); once answer text or a tool call follows, close it.
    return this.getCurrentContent().length === 0 && !this.currentStreamingMessage?.toolUses?.length
      ? '<thinking>' + this.thinkingBuffer
      : '<thinking>' + this.thinkingBuffer + '</thinking>\n\n';
  }

  /**
   * Get current accumulated content.
   */
  private getCurrentContent(): string {
    return this.contentChunks.join('');
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CHAT OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Send a chat message with streaming response.
   */
  async sendMessage(
    session: LocalAgentSession,
    message: string,
    agent: AgentProvider,
    options: {
      sessionId?: string;
      skipPermissions?: boolean;
      workingDirectory?: string;
      enableMemory?: boolean;
      systemPromptOverride?: string;
      includeHistory?: boolean;
      maxHistoryMessages?: number;
      // RAG options
      enableRag?: boolean;
      ragMaxResults?: number;
      ragSimilarityThreshold?: number;
      includeKeywordSearch?: boolean;
      includeSemanticSearch?: boolean;
      // Graph RAG options
      enableGraphRag?: boolean;
      graphRagMaxResults?: number;
      graphRagSearchType?: string;
      graphRagConversationId?: string;
      // Folder context injection
      folderId?: string;
      // Timeout (0 selects the server-owned five-minute default)
      timeoutSeconds?: number;
      // Inline attachments (images, text files)
      attachments?: MessageAttachment[];
    } = {}
  ): Promise<void> {
    // Create and store user message
    const userMessage = createUserMessage(session.id, message);
    this.liveMessageStart = session.messages.length;
    this.liveAgent = agent;
    this.liveTurnId = 0;
    this.lastLiveMessage = null;
    session.messages.push(userMessage);
    this.storageService.updateSession(session);

    // Create assistant message placeholder
    const assistantMessage = createAssistantMessage(session.id, agent);
    session.messages.push(assistantMessage);
    this.currentStreamingMessage = assistantMessage;
    this.streamStartTime = Date.now();

    // Reset streaming state
    this.isStreaming$.next(true);
    this.streamingContent$.next('');
    this.resetContentBuffer();

    // Build chat history if requested
    let chatHistory: ChatHistoryEntry[] | undefined;
    if (options.includeHistory !== false) {
      const maxHistory = options.maxHistoryMessages || 20;
      chatHistory = this.buildChatHistory(session, maxHistory);
    }

    // Build request with RAG options, folder context, and timeout
    const request: LocalAgentChatRequest = {
      message,
      sessionId: options.sessionId ?? session.id,
      agentName: agent.name,
      skipPermissions: options.skipPermissions ?? false,
      workingDirectory: options.workingDirectory,
      enableMemory: options.enableMemory ?? true,
      systemPromptOverride: options.systemPromptOverride,
      includeHistory: options.includeHistory !== false,
      chatHistory,
      // RAG configuration
      enableRag: options.enableRag ?? false,
      ragMaxResults: options.ragMaxResults ?? 5,
      ragSimilarityThreshold: options.ragSimilarityThreshold ?? 0.0,
      includeKeywordSearch: options.includeKeywordSearch ?? true,
      includeSemanticSearch: options.includeSemanticSearch ?? true,
      // Graph RAG configuration
      enableGraphRag: options.enableGraphRag ?? false,
      graphRagMaxResults: options.graphRagMaxResults ?? 5,
      graphRagSearchType: options.graphRagSearchType ?? 'LOCAL',
      graphRagConversationId: options.graphRagConversationId,
      // Folder context injection
      folderId: options.folderId,
      // Timeout configuration (0 = server-owned safety default)
      timeoutSeconds: options.timeoutSeconds ?? 300,
      // Attachments
      // Keep previews and other UI-only fields out of the wire payload. In particular,
      // previewUrl duplicates every image's base64 data and can otherwise double request memory.
      attachments: options.attachments?.map(attachment => ({
        filename: attachment.filename || attachment.name,
        mimeType: attachment.mimeType,
        base64Data: attachment.base64Data,
        textContent: attachment.textContent ?? attachment.content,
        isImage: attachment.isImage === true
      }))
    };

    console.debug('[LocalAgentChat] Sending request with RAG enabled:', request.enableRag, 'timeout:', request.timeoutSeconds, 's',
      'attachments:', request.attachments?.length ?? 0);

    // The harness endpoint owns bounded attachment materialization. Keeping one JSON/SSE
    // transport also removes the old /stream-with-files call, for which no server route existed.
    await this.streamWithFetch(session, request);
  }

  /**
   * Build chat history from session messages.
   */
  private buildChatHistory(session: LocalAgentSession, maxMessages: number): ChatHistoryEntry[] {
    // Exclude the current turn — the user message just pushed plus the streaming
    // assistant placeholder. The current message travels in request.message; keeping
    // it here would send it to the model twice.
    const messages = session.messages.slice(0, -2)
      .filter(m => !m.commandOnly)
      .slice(-maxMessages);
    return messages
      .filter(m => m.role === 'USER' || m.role === 'ASSISTANT')
      .map(m => ({
        role: m.role,
        content: m.content
      }));
  }

  /**
   * Stream response using fetch API (SSE).
   */
  private async streamWithFetch(session: LocalAgentSession, request: LocalAgentChatRequest): Promise<void> {
    const owner = new AbortController();
    this.currentAbortController = owner;
    try {
      console.debug('[LocalAgentChat] Starting stream request');
      this.currentProcessId = null;
      this.lastEventId = 0;
      this.reconnectable = false;
      this.transcriptStale = false;
      this.browserSessionId = request.sessionId || session.id;
      this.reconnectSeed = session;
      this.closeHarnessControls();
      this.harnessActivity = null;
      this.liveTurnTexts = [];
      this.thinkingBuffer = '';
      this.liveInputHistory = [];

      const response = await fetch(`${this.backendUrl}/agents/chat/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream'
        },
        body: JSON.stringify(request),
        signal: owner.signal
      });
      if (owner.signal.aborted || this.currentAbortController !== owner) return;

      console.debug('[LocalAgentChat] Response status:', response.status);

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`HTTP ${response.status}: ${errorText}`);
      }

      await this.consumeWithReconnect(session, response);

    } catch (error: any) {
      // Navigation and Stop both detach the transport; a late failure must not end a newer view/run.
      if (owner.signal.aborted || this.currentAbortController !== owner) return;
      if (error.name === 'AbortError') {
        console.debug('[LocalAgentChat] Stream transport detached');
      } else {
        console.error('[LocalAgentChat] Streaming error:', error);
        const message = error.message || 'Unknown error';
        if (this.currentStreamingMessage) {
          this.handleStreamError(session, message);
        } else {
          this.finalizeStreaming();
          this.streamingError$.next(message);
        }
      }
    }
  }

  /**
   * Read and process an SSE response stream. Shared by both JSON and multipart endpoints.
   */
  private async readSseStream(session: LocalAgentSession, response: Response): Promise<boolean> {
      const ownedAbortController = this.currentAbortController;
      const reader = response.body?.getReader();
      if (!reader) throw new Error('No response body');

      const decoder = new TextDecoder();
      let buffer = '';
      let currentEventType = 'message';
      let eventId = 0;
      let sawTerminalEvent = false;

      // A phone that slept can hold a socket that is silently dead: after a long absence, drop it
      // and replay from the cursor instead of waiting for a TCP timeout.
      let hiddenAt = typeof document !== 'undefined' && document.visibilityState === 'hidden' ? Date.now() : 0;
      let woke = false;
      const onVisibility = () => {
        if (document.visibilityState === 'hidden') { hiddenAt = hiddenAt || Date.now(); return; }
        if (hiddenAt && Date.now() - hiddenAt >= LocalAgentChatService.WAKE_RECONNECT_MS && this.reconnectable) {
          woke = true;
          reader.cancel().catch(() => undefined);
        }
        hiddenAt = 0;
      };
      if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisibility);

      const emitContentUpdate = () => {
        const fullContent = this.getCurrentContent();
        // The view shows the turn's reasoning above its answer, as the CLI does. The stored
        // message keeps the answer alone until the turn ends: reconnect resumes from it.
        this.streamingContentRaw$.next({ content: this.renderThinkingPrefix() + fullContent, epoch: this.contentEpoch });

        // Update message in session
        if (this.currentStreamingMessage) {
          this.currentStreamingMessage.content = fullContent;
        }
      };

      const processLine = (line: string) => {
        if (sawTerminalEvent || this.currentAbortController !== ownedAbortController) return;
        if (line.startsWith('id:')) {
          eventId = Number(line.substring(3).trim());
          if (!Number.isSafeInteger(eventId) || eventId <= 0) throw new Error('Invalid replay event id');
          return;
        }
        if (line.startsWith('event:')) {
          currentEventType = line.substring(6).trim();
          return;
        }

        if (line.startsWith('data:')) {
          const data = line.substring(5).trim();
          if (!data) return;
          if (eventId && eventId <= this.lastEventId) { currentEventType = 'message'; eventId = 0; return; }
          if (eventId && eventId !== this.lastEventId + 1) throw new Error('Replay event gap; reconnect from saved history');

          try {
            const parsed = JSON.parse(data);
            // Advance before terminal observers can synchronously start a different run.
            if (eventId) this.lastEventId = eventId;
            eventId = 0;
            switch (currentEventType) {
              case 'harness_session':
                session.metadata = {
                  ...(session.metadata || {}),
                  harnessSessionId: parsed.session_id,
                  engine: 'kompile-cli-main',
                  provider: parsed.provider,
                  model: parsed.model
                };
                this.storageService.updateSession(session);
                // The team the session started with; a resumed run restores it. None: no team.
                this.rememberWorkflowTeam(this.browserSessionId, LocalAgentChatService.workflowTeamOf(parsed.workflow));
                break;

              case 'title':
                if (typeof parsed.title === 'string' && parsed.title.trim()
                    && parsed.session_id === session.metadata?.['harnessSessionId']) {
                  this.sessionTitle$.next({ sessionId: this.browserSessionId, title: parsed.title.trim() });
                }
                break;

              case 'resync':
                // The server skipped events this page slept through; the transcript fills them in at the end.
                if (Number.isSafeInteger(parsed.after) && parsed.after > this.lastEventId) this.lastEventId = parsed.after;
                this.transcriptStale = true;
                break;

              case 'queued':
                this.reconnectable = parsed.reconnectable === true;
                if (typeof parsed.processId === 'string') this.currentProcessId = parsed.processId;
                this.saveReconnectBookmark(session);
                break;

              case 'start':
                // Capture processId from start event for cancellation
                if (parsed.processId) {
                  this.currentProcessId = parsed.processId;
                  console.debug('[LocalAgentChat] Process started:', this.currentProcessId);
                }
                break;

              case 'activity':
                if (Array.isArray(parsed.processes) && Array.isArray(parsed.tasks)) {
                  this.harnessActivity = parsed as HarnessActivity;
                  // A finishing run still streams its end; input typed meanwhile waits for the next run.
                  this.liveControlsReady = parsed.controlsOpen !== false;
                }
                break;

              case 'control': {
                const pending = this.pendingControls.get(parsed.requestId);
                if (pending) {
                  clearTimeout(pending.timer);
                  this.pendingControls.delete(parsed.requestId);
                  if (parsed.closed === true) pending.reject(new HarnessRunClosedError(parsed.message || 'Run closed'));
                  else pending.resolve(parsed as HarnessControlReply);
                }
                break;
              }

              case 'turn_started':
                this.startLiveTurn(session, parsed);
                break;

              case 'turn_complete':
                // A turn boundary is not the end of background work or the SSE connection.
                if (this.liveTurnId > 0) {
                  if (parsed.turnId === this.liveTurnId && this.currentStreamingMessage) {
                    this.currentStreamingMessage.content =
                      this.renderThinkingPrefix() + (parsed.text || '');
                    this.currentStreamingMessage.streaming = false;
                    this.currentStreamingMessage.latencyMs = Date.now() - this.streamStartTime;
                    this.lastLiveMessage = this.currentStreamingMessage;
                    this.currentStreamingMessage = null;
                    this.publishLiveMessages(session);
                  }
                } else if (typeof parsed.text === 'string') {
                  this.liveTurnTexts.push(parsed.text);
                  this.resetContentBuffer();
                  this.accumulateContent(this.liveTurnTexts.join('\n\n') + '\n\n');
                  emitContentUpdate();
                }
                break;

              case 'command':
                // Only the CLI resolves commands. Persist their display separately from model history.
                this.handleCommandOutcome(session, parsed);
                break;

              case 'chunk':
                if (typeof parsed === 'string') {
                  this.accumulateContent(parsed);
                  emitContentUpdate();
                }
                break;

              case 'thinking':
                // Model reasoning deltas: display-only chrome. Routed into a
                // <thinking> block prepended to the message content so the shared
                // renderer shows the same collapsible thinking UI as the CLI REPL.
                if (typeof parsed === 'string' && parsed.length > 0) {
                  this.thinkingBuffer += parsed;
                  if (this.currentStreamingMessage) emitContentUpdate();
                }
                break;

              case 'superseded':
                sawTerminalEvent = true;
                this.reconnectable = false; // don't fight the replacement subscriber with automatic reconnects
                this.handleStreamError(session, 'Run connected in another view. Work continues there.');
                break;

              case 'cancelled':
                sawTerminalEvent = true;
                this.forgetReconnectBookmark();
                // Process was cancelled by user
                console.debug('[LocalAgentChat] Process cancelled:', parsed);
                if (this.currentStreamingMessage) {
                  this.currentStreamingMessage.streaming = false;
                  this.currentStreamingMessage.content = this.getCurrentContent() + '\n\n[Stopped]';
                  this.currentStreamingMessage.latencyMs = Date.now() - this.streamStartTime;
                  this.storageService.updateSession(session);
                }
                this.isStreaming$.next(false);
                this.currentStreamingMessage = null;
                this.currentProcessId = null;
                currentEventType = 'message'; // Reset before returning
                return; // Exit early

              case 'tool_use': {
                console.debug('[LocalAgentChat] Tool use:', parsed);
                const call = this.toToolCall(parsed);
                this.toolUse$.next(call);
                this.recordToolCall(call);
                if (this.thinkingBuffer) emitContentUpdate(); // closes the reasoning block above the call
                break;
              }

              case 'tool_result':
                console.debug('[LocalAgentChat] Tool completed:', parsed);
                this.result$.next({
                  durationMs: parsed.durationMs || 0,
                  numTurns: 0,
                  isError: parsed.ok === false
                });
                // Harness completions carry the CLI's row and detail for the call's card.
                if (parsed.status) this.recordToolCall(this.toToolCall(parsed));
                break;

              case 'result':
                console.debug('[LocalAgentChat] Result:', parsed);
                this.result$.next(parsed as ResultEvent);
                if (this.currentStreamingMessage) {
                  this.currentStreamingMessage.tokenCount = parsed.numTurns;
                }
                break;

              case 'files_modified':
                console.debug('[LocalAgentChat] Files modified:', parsed);
                this.filesModified$.next(parsed as string[]);
                if (this.currentStreamingMessage) {
                  this.currentStreamingMessage.modifiedFiles = parsed;
                }
                break;

              case 'sources':
                console.debug('[LocalAgentChat] Sources received:', parsed);
                const sources = parsed as RetrievedSource[];
                this.sources$.next(sources);
                if (this.currentStreamingMessage) {
                  this.currentStreamingMessage.sources = sources;
                }
                break;

              case 'reasoning_trace':
                console.debug('[LocalAgentChat] Reasoning trace received:', parsed);
                if (this.currentStreamingMessage) {
                  if (!this.currentStreamingMessage.reasoningTrails) {
                    this.currentStreamingMessage.reasoningTrails = [];
                  }
                  this.currentStreamingMessage.reasoningTrails.push(parsed as ReasoningTrailDto);
                }
                break;

              case 'rag_metrics':
                console.debug('[LocalAgentChat] RAG metrics received:', parsed);
                if (this.currentStreamingMessage) {
                  this.currentStreamingMessage.ragMetrics = parsed as { retrievalMs: number; documentsRetrieved: number };
                }
                break;

              case 'query_info':
                console.debug('[LocalAgentChat] Query info received:', parsed);
                if (this.currentStreamingMessage) {
                  this.currentStreamingMessage.queryInfo = parsed as { originalQuery: string; rewrittenQuery: string; wasRewritten: boolean; intent?: string };
                }
                break;

              case 'stats':
                console.debug('[LocalAgentChat] Stats received:', parsed);
                const stats = parsed as ChatStats;
                this.chatStats$.next(stats);
                if (this.currentStreamingMessage && stats.tokenMetrics) {
                  this.currentStreamingMessage.tokenMetrics = stats.tokenMetrics;
                }
                break;

              case 'compaction':
                // Backend auto-compacted the history it was sent — surface it so the
                // chat window can mirror the compacted state and show a notice.
                console.debug('[LocalAgentChat] Compaction event:', parsed);
                this.ngZone.run(() => this.compaction$.next(parsed as CompactionEvent));
                break;

              case 'complete':
                sawTerminalEvent = true;
                this.forgetReconnectBookmark();
                console.debug('[LocalAgentChat] Complete message received');
                this.handleStreamComplete(session, parsed);
                break;

              case 'error':
                sawTerminalEvent = true;
                this.forgetReconnectBookmark();
                console.error('[LocalAgentChat] Error event:', parsed);
                this.handleStreamError(session, typeof parsed === 'string' ? parsed : JSON.stringify(parsed));
                break;

              default:
                if (typeof parsed === 'string') {
                  this.accumulateContent(parsed);
                  emitContentUpdate();
                }
            }

            if (this.reconnectable && !sawTerminalEvent && (Date.now() - this.lastCheckpointAt >= 250
                || currentEventType === 'turn_started' || currentEventType === 'turn_complete')) this.saveReconnectBookmark(session);
            currentEventType = 'message';
          } catch (error) {
            if (currentEventType !== 'chunk' && currentEventType !== 'message') throw error;
            // Plain text content
            if (currentEventType === 'chunk' || currentEventType === 'message') {
              this.accumulateContent(data + '\n');
              emitContentUpdate();
            }
          }
        }
      };

      try { while (true) {
        const { done, value } = await reader.read();
        // Only whole events advanced the cursor, so a partial line in the buffer is replayed intact.
        if (woke) return false;

        if (value) {
          const rawChunk = decoder.decode(value, { stream: !done });
          buffer += rawChunk;

          const lines = buffer.split('\n');
          buffer = lines.pop() || '';

          for (const line of lines) {
            processLine(line);
          }
        }

        if (done) {
          // Process remaining buffer
          if (buffer.trim()) {
            const remainingLines = buffer.split('\n');
            for (const line of remainingLines) {
              if (line.trim()) {
                processLine(line);
              }
            }
          }
          break;
        }
      } } finally {
        if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisibility);
        reader.releaseLock();
      }

      if (!sawTerminalEvent && (ownedAbortController?.signal.aborted
          || this.currentAbortController !== ownedAbortController)) return false;
      if (!sawTerminalEvent) {
        if (this.reconnectable) return false;
        throw new Error('Kompile harness stream ended without a terminal event');
      }
      if (this.currentAbortController === ownedAbortController) this.finalizeStreaming();
      return true;
  }

  /**
   * Handle stream completion.
   */
  private handleStreamComplete(session: LocalAgentSession, data: any): void {
    if (data.commandOutcome) this.handleCommandOutcome(session, data.commandOutcome);
    if (this.currentStreamingMessage) {
      this.currentStreamingMessage.streaming = false;
      this.currentStreamingMessage.latencyMs = Date.now() - this.streamStartTime;

      if (this.liveTurnTexts.length) {
        this.currentStreamingMessage.content = this.renderThinkingPrefix()
          + this.liveTurnTexts.join('\n\n');
      } else if (data.content) {
        // The run's result text carries no reasoning: keep the streamed block above it, closed.
        this.currentStreamingMessage.content = (this.thinkingBuffer
          ? '<thinking>' + this.thinkingBuffer + '</thinking>\n\n' : '') + data.content;
      } else if (this.thinkingBuffer.length > 0) {
        // Thinking streamed but no answer text (e.g. turn ended during reasoning):
        // keep the reasoning visible instead of dropping it.
        this.currentStreamingMessage.content = this.renderThinkingPrefix();
      }
      if (data.rawResponse) {
        this.currentStreamingMessage.rawResponse = data.rawResponse;
      }

      this.storageService.updateSession(session);
      const completed = this.currentStreamingMessage;
      // Release ownership before notifying listeners that may immediately start another run.
      this.finalizeStreaming();
      this.streamingComplete$.next(completed);
    } else {
      const completed = this.lastLiveMessage;
      this.finalizeStreaming();
      if (completed) this.streamingComplete$.next(completed);
    }
  }

  /** A tool event as stored on its message; the answer length so far anchors its card. */
  private toToolCall(parsed: any): ToolUseEvent {
    return {
      ...parsed,
      tool: parsed.tool ?? parsed.toolName ?? '',
      input: typeof parsed.input === 'string' ? parsed.input : parsed.input === undefined ? '' : JSON.stringify(parsed.input),
      textOffset: this.getCurrentContent().length
    };
  }

  /**
   * The harness sends started then completed per call. A completion merges into its started
   * call by callId, or into the latest open call of that tool when the model gave no id.
   * Each change stores a fresh array, so views holding the previous one see the update.
   */
  private recordToolCall(call: ToolUseEvent): void {
    const message = this.currentStreamingMessage;
    // Only harness calls are kept: other lanes echo their calls into the answer text.
    if (!message || !call.status) return;
    const calls = message.toolUses ?? [];
    let index = -1;
    if (call.status === 'completed') {
      for (let i = calls.length - 1; i >= 0 && index < 0; i--) {
        const open = calls[i];
        if (open.status === 'started' && (call.callId ? open.callId === call.callId : open.tool === call.tool)) index = i;
      }
    }
    message.toolUses = index < 0 ? [...calls, call] : calls.map((open, i) => i !== index ? open
      : { ...open, status: call.status, ok: call.ok, durationMs: call.durationMs, detail: call.detail });
    this.toolCalls$.next(message.toolUses);
  }

  private handleCommandOutcome(session: LocalAgentSession, outcome: CommandOutcome): void {
    // Dedicated bus first: command-center modal panels update in place and do
    // not depend on a transcript turn being in flight.
    this.commandOutcomeBus$.next(outcome);
    const message = this.currentStreamingMessage;
    if (!message) return;
    const index = session.messages.indexOf(message);
    const user = index > 0 ? session.messages[index - 1] : undefined;
    if (user?.role === 'USER') {
      user.commandOnly = true;
      user.commandOutcome = outcome;
    }
    // Use the existing system-message display, never attribute command text to the model.
    message.role = 'SYSTEM';
    message.agent = undefined;
    message.tokenMetrics = undefined;
    message.tokenCount = undefined;
    message.commandOnly = true;
    message.commandOutcome = outcome;
    message.content = typeof outcome.text === 'string' ? outcome.text : '';
    this.resetContentBuffer();
    this.accumulateContent(message.content);
    this.streamingContent$.next(message.content);
    this.storageService.updateSession(session);
  }

  /**
   * Handle stream error.
   */
  private handleStreamError(session: LocalAgentSession, errorMessage: string): void {
    if (this.currentStreamingMessage) {
      this.currentStreamingMessage.streaming = false;
      this.currentStreamingMessage.error = true;
      this.currentStreamingMessage.errorMessage = errorMessage;
      this.currentStreamingMessage.content = this.getCurrentContent() || `Error: ${errorMessage}`;
      this.currentStreamingMessage.latencyMs = Date.now() - this.streamStartTime;

      this.storageService.updateSession(session);
    }

    this.finalizeStreaming();
    this.streamingError$.next(errorMessage);
  }

  /** Clear request-scoped transport state after a terminal event or handled failure. */
  private finalizeStreaming(detached = false): void {
    this.closeHarnessControls(detached);
    this.isStreaming$.next(false);
    this.currentStreamingMessage = null;
    this.currentProcessId = null;
    this.currentAbortController = null;
  }

  /** Leave the socket without cancelling owned work; the existing timeout still applies. */
  detachStreaming(): void {
    // Flush the latest message and cursor, including deltas still inside the 250 ms checkpoint throttle.
    if (this.reconnectable) this.saveReconnectBookmark();
    this.currentAbortController?.abort();
    this.finalizeStreaming(true);
  }

  /** Explicit Stop: abort the browser stream AND cancel the server-owned work. */
  cancelStreaming(): void {
    this.forgetReconnectBookmark();
    this.closeHarnessControls();
    console.debug('[LocalAgentChat] Cancel streaming requested');

    // Abort the fetch request
    if (this.currentAbortController) {
      console.debug('[LocalAgentChat] Aborting fetch request');
      this.currentAbortController.abort();
      this.currentAbortController = null;
    }

    // Call backend to kill the process
    if (this.currentProcessId) {
      console.debug('[LocalAgentChat] Sending cancel request to backend for process:', this.currentProcessId);
      this.cancelBackendProcess(this.currentProcessId);
    }

    // Update current message
    if (this.currentStreamingMessage) {
      this.currentStreamingMessage.streaming = false;
      this.currentStreamingMessage.content = this.getCurrentContent() + '\n\n[Stopped]';
      this.currentStreamingMessage.latencyMs = Date.now() - this.streamStartTime;
    }

    this.isStreaming$.next(false);
    this.currentStreamingMessage = null;
    this.currentProcessId = null;
    this.resetContentBuffer();
  }

  /**
   * Send cancel request to backend to kill the agent process.
   */
  private cancelBackendProcess(processId: string): void {
    fetch(`${this.backendUrl}/agents/chat/cancel/${processId}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      }
    })
    .then(response => response.json())
    .then(result => {
      console.debug('[LocalAgentChat] Backend cancel result:', result);
    })
    .catch(error => {
      console.error('[LocalAgentChat] Failed to cancel backend process:', error);
    });
  }

  /**
   * Check if currently streaming.
   */
  getCurrentProcessId(): string | null {
    return this.currentProcessId;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SESSION OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Create a new chat session.
   */
  createSession(name?: string, agent?: AgentProvider): LocalAgentSession {
    const session = createNewSession(name, agent);
    this.storageService.updateSession(session);
    return session;
  }

  /**
   * Get or create a session for a tab.
   */
  getOrCreateSessionForTab(tab: ChatTabState): LocalAgentSession {
    if (tab.session) {
      return tab.session;
    }

    const session = this.createSession(tab.displayName, tab.selectedAgent || undefined);
    tab.session = session;
    this.storageService.updateTab(tab);
    return session;
  }

  /**
   * Clear messages in a session.
   */
  clearSession(session: LocalAgentSession): void {
    session.messages = [];
    session.messageCount = 0;
    session.updatedAt = new Date().toISOString();
    this.storageService.updateSession(session);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // BRANCHING / FORK FUNCTIONALITY
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Create a fork/branch from a specific message.
   * The new branch starts after the specified message (or its parent for assistant messages).
   * Returns the parent message ID where the fork occurs.
   */
  createBranch(session: LocalAgentSession, fromMessage: LocalAgentMessage): string {
    // Initialize allMessages if not present
    if (!session.allMessages) {
      session.allMessages = [...session.messages];
    }

    // Find the fork point - for user messages, fork from parent; for assistant, fork from the user message before it
    let forkPointId: string;
    if (fromMessage.role === 'USER') {
      forkPointId = fromMessage.parentId || '';
    } else {
      // For assistant messages, the fork point is the user message that prompted it
      const msgIndex = session.messages.findIndex(m => m.id === fromMessage.id);
      if (msgIndex > 0) {
        forkPointId = session.messages[msgIndex - 1].id;
      } else {
        forkPointId = '';
      }
    }

    return forkPointId;
  }

  /**
   * Add a new message as a sibling (alternative) to an existing message.
   * This creates a branch in the conversation.
   */
  addSiblingMessage(
    session: LocalAgentSession,
    existingMessage: LocalAgentMessage,
    newContent: string,
    agent?: AgentProvider
  ): LocalAgentMessage {
    // Initialize allMessages if not present
    if (!session.allMessages) {
      session.allMessages = [...session.messages];
    }

    // Create the new sibling message
    const newMessage: LocalAgentMessage = {
      id: `msg-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
      sessionId: session.id,
      role: existingMessage.role,
      content: newContent,
      timestamp: new Date().toISOString(),
      agent: agent || existingMessage.agent,
      parentId: existingMessage.parentId,
      streaming: false
    };

    // Update sibling relationships
    const siblings = this.getSiblings(session, existingMessage);
    const allSiblingIds = [...siblings.map(s => s.id), newMessage.id];

    // Update all siblings with the new sibling info
    for (const sibling of siblings) {
      sibling.siblingIds = allSiblingIds.filter(id => id !== sibling.id);
      sibling.siblingCount = allSiblingIds.length;
    }

    newMessage.siblingIds = allSiblingIds.filter(id => id !== newMessage.id);
    newMessage.siblingCount = allSiblingIds.length;
    newMessage.siblingIndex = allSiblingIds.length - 1;

    // Add to allMessages
    session.allMessages.push(newMessage);

    this.storageService.updateSession(session);
    return newMessage;
  }

  /**
   * Get all sibling messages (including the message itself).
   */
  getSiblings(session: LocalAgentSession, message: LocalAgentMessage): LocalAgentMessage[] {
    const allMessages = session.allMessages || session.messages;

    if (!message.siblingIds || message.siblingIds.length === 0) {
      return [message];
    }

    const siblings = allMessages.filter(m =>
      m.id === message.id || (message.siblingIds && message.siblingIds.includes(m.id))
    );

    return siblings.sort((a, b) => {
      const aIndex = a.siblingIndex ?? 0;
      const bIndex = b.siblingIndex ?? 0;
      return aIndex - bIndex;
    });
  }

  /**
   * Switch to a different branch by selecting a sibling message.
   * Updates the session's messages array to show the selected branch.
   */
  switchToBranch(session: LocalAgentSession, targetMessage: LocalAgentMessage): void {
    if (!session.allMessages) {
      session.allMessages = [...session.messages];
    }

    // Find the index in current messages where this message or its sibling exists
    const currentMessages = session.messages;
    let insertIndex = -1;

    for (let i = 0; i < currentMessages.length; i++) {
      const msg = currentMessages[i];
      if (msg.id === targetMessage.id) {
        // Already showing this message
        return;
      }
      if (targetMessage.siblingIds && targetMessage.siblingIds.includes(msg.id)) {
        insertIndex = i;
        break;
      }
    }

    if (insertIndex === -1) {
      console.warn('Could not find position for branch switch');
      return;
    }

    // Replace the message at insertIndex with targetMessage
    // Also need to rebuild the rest of the conversation from this point
    const newMessages = currentMessages.slice(0, insertIndex);
    newMessages.push(targetMessage);

    // Find children of targetMessage and add them
    this.appendBranchChildren(session, targetMessage, newMessages);

    session.messages = newMessages;
    session.currentBranchPath = newMessages.map(m => m.id);

    this.storageService.updateSession(session);
  }

  /**
   * Recursively append children messages following the current branch.
   */
  private appendBranchChildren(
    session: LocalAgentSession,
    parentMessage: LocalAgentMessage,
    messages: LocalAgentMessage[]
  ): void {
    const allMessages = session.allMessages || [];

    // Find direct children of this message
    const children = allMessages.filter(m => m.parentId === parentMessage.id);

    if (children.length === 0) return;

    // If there are multiple children (branches), pick the first one or the one matching current path
    let selectedChild: LocalAgentMessage;
    if (children.length === 1) {
      selectedChild = children[0];
    } else {
      // Check if any child is in the current branch path
      const pathChild = children.find(c =>
        session.currentBranchPath && session.currentBranchPath.includes(c.id)
      );
      selectedChild = pathChild || children[0];
    }

    messages.push(selectedChild);
    this.appendBranchChildren(session, selectedChild, messages);
  }

  /**
   * Get the number of branches at a specific message point.
   */
  getBranchCount(session: LocalAgentSession, message: LocalAgentMessage): number {
    return message.siblingCount || 1;
  }

  /**
   * Get the current branch index for a message.
   */
  getCurrentBranchIndex(session: LocalAgentSession, message: LocalAgentMessage): number {
    const siblings = this.getSiblings(session, message);
    const currentIndex = siblings.findIndex(s => s.id === message.id);
    return currentIndex >= 0 ? currentIndex : 0;
  }

  /**
   * Navigate to next sibling branch.
   */
  nextBranch(session: LocalAgentSession, message: LocalAgentMessage): LocalAgentMessage | null {
    const siblings = this.getSiblings(session, message);
    const currentIndex = siblings.findIndex(s => s.id === message.id);

    if (currentIndex < siblings.length - 1) {
      const nextSibling = siblings[currentIndex + 1];
      this.switchToBranch(session, nextSibling);
      return nextSibling;
    }
    return null;
  }

  /**
   * Navigate to previous sibling branch.
   */
  prevBranch(session: LocalAgentSession, message: LocalAgentMessage): LocalAgentMessage | null {
    const siblings = this.getSiblings(session, message);
    const currentIndex = siblings.findIndex(s => s.id === message.id);

    if (currentIndex > 0) {
      const prevSibling = siblings[currentIndex - 1];
      this.switchToBranch(session, prevSibling);
      return prevSibling;
    }
    return null;
  }

  /**
   * Check if a message has multiple branches.
   */
  hasBranches(message: LocalAgentMessage): boolean {
    return (message.siblingCount || 1) > 1;
  }

  /**
   * Format a session's messages as copyable text.
   */
  formatSessionAsText(session: LocalAgentSession): string {
    const lines: string[] = [];
    lines.push(`# ${session.name}`);
    lines.push(`Created: ${new Date(session.createdAt).toLocaleString()}`);
    lines.push('');
    lines.push('---');
    lines.push('');

    for (const msg of session.messages) {
      const role = msg.role === 'USER' ? 'You' : msg.role === 'SYSTEM' ? 'System' : (msg.agent?.displayName || 'Assistant');
      const time = new Date(msg.timestamp).toLocaleTimeString();
      lines.push(`## ${role} (${time})`);
      lines.push('');
      lines.push(msg.content);
      lines.push('');

      // Include sources if present
      if (msg.sources && msg.sources.length > 0) {
        lines.push('**Sources:**');
        for (const source of msg.sources) {
          lines.push(`- [${source.index}] ${source.sourceName} (${(source.score * 100).toFixed(1)}%)`);
        }
        lines.push('');
      }

      lines.push('---');
      lines.push('');
    }

    return lines.join('\n');
  }

  /**
   * Format a single message as copyable text.
   */
  formatMessageAsText(message: LocalAgentMessage): string {
    const lines: string[] = [];
    const role = message.role === 'USER' ? 'You' : message.role === 'SYSTEM' ? 'System' : (message.agent?.displayName || 'Assistant');
    const time = new Date(message.timestamp).toLocaleTimeString();

    lines.push(`**${role}** (${time})`);
    lines.push('');
    lines.push(message.content);

    // Include sources if present
    if (message.sources && message.sources.length > 0) {
      lines.push('');
      lines.push('**Sources:**');
      for (const source of message.sources) {
        lines.push(`- [${source.index}] ${source.sourceName} (${(source.score * 100).toFixed(1)}%)`);
      }
    }

    return lines.join('\n');
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // OBSERVABLES
  // ═══════════════════════════════════════════════════════════════════════════════

  getStreamingContent(): Observable<string> {
    return this.streamingContent$.asObservable();
  }

  getStreamingComplete(): Observable<LocalAgentMessage> {
    return this.streamingComplete$.asObservable();
  }

  getStreamingError(): Observable<string> {
    return this.streamingError$.asObservable();
  }

  getIsStreaming(): Observable<boolean> {
    return this.isStreaming$.asObservable();
  }

  getToolUse(): Observable<ToolUseEvent> {
    return this.toolUse$.asObservable();
  }

  /** The streaming message's tool calls, re-emitted whole after each tool event. */
  getToolCalls(): Observable<ToolUseEvent[]> {
    return this.toolCalls$.asObservable();
  }

  getResult(): Observable<ResultEvent> {
    return this.result$.asObservable();
  }

  getFilesModified(): Observable<string[]> {
    return this.filesModified$.asObservable();
  }

  getSources(): Observable<RetrievedSource[]> {
    return this.sources$.asObservable();
  }

  getSessionTitle(): Observable<{ sessionId: string; title: string }> {
    return this.sessionTitle$.asObservable();
  }

  getChatStats(): Observable<ChatStats> {
    return this.chatStats$.asObservable();
  }

  getCompaction(): Observable<CompactionEvent> {
    return this.compaction$.asObservable();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CONTEXT BUDGET & COMPACTION
  // ═══════════════════════════════════════════════════════════════════════════════

  /** The context budget of the model behind an agent (window, output cap, input budget). */
  getContextBudget(agentName: string, workingDirectory?: string): Observable<ContextBudget> {
    const params: { [key: string]: string } = { agentName };
    if (workingDirectory) params['workingDirectory'] = workingDirectory;
    return this.http.get<ContextBudget>(
      `${this.backendUrl}/agents/chat/context-budget`,
      { params });
  }

  /**
   * Manually compact a session's history: the backend summarizes older messages via
   * the agent's own lane and returns the compacted history to adopt.
   */
  compactChat(agentName: string, chatHistory: ChatHistoryEntry[],
              focusInstruction?: string): Observable<CompactChatResponse> {
    return this.http.post<CompactChatResponse>(
      `${this.backendUrl}/agents/chat/compact`,
      { agentName, chatHistory, focusInstruction });
  }

  /**
   * Quiet session-configuration snapshot: resolves the model / role / fast /
   * reminders / loops / queue menus headlessly through the CLI. Sends no chat
   * messages and creates no transcript entries — the Session Configuration
   * dialog populates from this call alone.
   */
  getSessionConfig(sessionId?: string, workingDirectory?: string,
                   modelVendor?: string): Observable<SessionConfigSnapshot> {
    const params: { [key: string]: string } = {};
    if (sessionId) params['sessionId'] = sessionId;
    if (workingDirectory) params['workingDirectory'] = workingDirectory;
    if (modelVendor) params['modelVendor'] = modelVendor;
    return this.http.get<SessionConfigSnapshot>(
      `${this.backendUrl}/agents/chat/session-config`, { params });
  }

  /**
   * The CLI setup wizard for an existing chat: 'catalog' discovers the route options seeded
   * from the chat's own configuration, 'update' re-pins its vendor, authentication, model and
   * effort. The caller shows its own errors, so failures skip the global error snackbar.
   */
  setupSession<T>(sessionId: string, workingDirectory: string | undefined, action: 'catalog' | 'update',
                  selection: object): Observable<T> {
    return this.http.post<T>(`${this.backendUrl}/agents/chat/session-setup`,
      { sessionId, workingDirectory, action, selection },
      { context: new HttpContext().set(SKIP_ERROR_SNACKBAR, true) });
  }

  /**
   * The session's insight rows, read headlessly through the CLI like getSessionConfig: no chat
   * messages, no transcript entries. The insights drawer polls this and shows its own
   * unavailable state, so failures skip the global error snackbar.
   */
  getSessionInsights(sessionId?: string, workingDirectory?: string): Observable<SessionInsightsSnapshot> {
    const params: { [key: string]: string } = {};
    if (sessionId) params['sessionId'] = sessionId;
    if (workingDirectory) params['workingDirectory'] = workingDirectory;
    return this.http.get<SessionInsightsSnapshot>(
      `${this.backendUrl}/agents/chat/session-insights`,
      { params, context: new HttpContext().set(SKIP_ERROR_SNACKBAR, true) });
  }

  /**
   * One topic's report over every chat session; no topic means the overview. The question
   * narrows it, with a window ("last 30 days") or a subject ("flags for bash"). The insights
   * page shows a failed read itself, so failures skip the global error snackbar.
   */
  getInsightsReport(topic?: string, question?: string, workingDirectory?: string): Observable<InsightsTopicReport> {
    const params: { [key: string]: string } = {};
    if (topic) params['topic'] = topic;
    if (question) params['question'] = question;
    if (workingDirectory) params['workingDirectory'] = workingDirectory;
    return this.http.get<InsightsTopicReport>(
      `${this.backendUrl}/agents/chat/insights`,
      { params, context: new HttpContext().set(SKIP_ERROR_SNACKBAR, true) });
  }

  getToolInvocationPage(sessionId: string, invocationId: string, field: string, offset: number): Observable<InvocationContentPage> {
    return this.http.get<InvocationContentPage>(`${this.backendUrl}/agents/chat/tool-details`, {
      params: { sessionId, invocationId, field, offset: String(offset) },
      context: new HttpContext().set(SKIP_ERROR_SNACKBAR, true)
    });
  }

  /** insights.json, which the insights page edits and shows its own errors for. */
  getInsightsSettings(): Observable<InsightsSettingsView> {
    return this.http.get<InsightsSettingsView>(
      `${this.backendUrl}/agents/chat/insights/config`,
      { context: new HttpContext().set(SKIP_ERROR_SNACKBAR, true) });
  }

  /** Changes the insights settings {@code changes} names; the others keep their values. */
  saveInsightsSettings(changes: Partial<InsightsSettings>): Observable<InsightsSettingsView> {
    return this.http.put<InsightsSettingsView>(
      `${this.backendUrl}/agents/chat/insights/config`, changes,
      { context: new HttpContext().set(SKIP_ERROR_SNACKBAR, true) });
  }

  // Synchronous getters
  isCurrentlyStreaming(): boolean {
    return this.isStreaming$.value;
  }

  getCurrentStreamingContent(): string {
    return this.streamingContent$.value;
  }
}
