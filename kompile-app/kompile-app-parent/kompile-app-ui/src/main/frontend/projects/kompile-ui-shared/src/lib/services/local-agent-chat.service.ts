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
import { HttpClient } from '@angular/common/http';
import { Observable, Subject, BehaviorSubject } from 'rxjs';
import { throttleTime } from 'rxjs/operators';
import { BaseService } from './base.service';
import { ChatStorageService } from './chat-storage.service';
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
  MessageAttachment
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
  role?: CommandEventData;
  fast?: CommandEventData;
  reminders?: CommandEventData;
  remindersGlobal?: CommandEventData;
  loops?: CommandEventData;
  loopsGlobal?: CommandEventData;
  queue?: CommandEventData;
  continue?: CommandEventData;
  judge?: CommandEventData;
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
  processes: HarnessActivityEntry[];
  tasks: HarnessActivityEntry[];
  subagents?: HarnessSubagent[];
}
export type HarnessControlAction = 'background' | 'process_list' | 'process_output' | 'process_kill' | 'input' | 'subagent_input' | 'subagent_cancel';
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
  output?: string;
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
  private result$ = new Subject<ResultEvent>();
  private filesModified$ = new Subject<string[]>();
  private sources$ = new Subject<RetrievedSource[]>();
  private chatStats$ = new Subject<ChatStats>();
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

  getReconnectBookmark(browserSessionId: string): HarnessReconnectBookmark | null {
    try {
      const value = JSON.parse(sessionStorage.getItem('kompile-live-run:' + browserSessionId) || 'null');
      return value && value.backendUrl === this.backendUrl && value.expiresAt > Date.now()
        && typeof value.runId === 'string' && value.runId.startsWith('harness-')
        && Array.isArray(value.seed?.messages) && value.agent && Number.isInteger(value.messageStart)
        && value.messageStart >= 0 && value.messageStart + 2 <= value.seed.messages.length
        && (value.cursor === undefined || (Number.isSafeInteger(value.cursor) && value.cursor >= 0))
        ? value as HarnessReconnectBookmark : null;
    } catch { return null; }
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
      if (json.length <= 1_048_576) sessionStorage.setItem('kompile-live-run:' + this.browserSessionId, json);
    } catch { /* storage is optional; in-page replay still works */ }
  }

  private forgetReconnectBookmark(): void {
    try { sessionStorage.removeItem('kompile-live-run:' + this.browserSessionId); } catch { }
  }

  async stopReconnectRun(browserSessionId: string): Promise<void> {
    const saved = this.getReconnectBookmark(browserSessionId);
    if (!saved) return;
    const response = await fetch(`${this.backendUrl}/agents/chat/cancel/${encodeURIComponent(saved.runId)}`, { method: 'POST' });
    if (!response.ok) throw new Error('Could not stop the saved run');
    try { sessionStorage.removeItem('kompile-live-run:' + browserSessionId); } catch { }
  }

  async resumeRun(browserSessionId: string): Promise<void> {
    if (this.currentAbortController) throw new Error('A run is already connected');
    const saved = this.getReconnectBookmark(browserSessionId);
    if (!saved) throw new Error('No reconnectable run saved in this tab');
    const session = saved.seed;
    this.browserSessionId = browserSessionId;
    this.reconnectSeed = JSON.parse(JSON.stringify(saved.seed));
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
    try {
      const response = await this.fetchReplay(saved.runId);
      this.publishLiveMessages(session);
      await this.consumeWithReconnect(session, response);
    } catch (error) {
      if (!(error instanceof DOMException && error.name === 'AbortError'))
        this.handleStreamError(session, error instanceof Error ? error.message : 'Reconnect failed');
    }
  }

  private async fetchReplay(runId: string): Promise<Response> {
    const response = await fetch(`${this.backendUrl}/agents/chat/events/${encodeURIComponent(runId)}?after=${this.lastEventId}`, {
      headers: { Accept: 'text/event-stream' }, signal: this.currentAbortController?.signal
    });
    if (!response.ok) throw new Error(`Cannot replay run (${response.status}). Stop the saved run or inspect its transcript; no work was restarted.`);
    return response;
  }

  private async consumeWithReconnect(session: LocalAgentSession, initial: Response): Promise<void> {
    let response = initial;
    const owner = this.currentAbortController;
    for (let attempt = 0; ; attempt++) {
      try { if (await this.readSseStream(session, response)) return; }
      catch (error) {
        if (owner?.signal.aborted || this.currentAbortController !== owner) return;
        if (!this.reconnectable || !(error instanceof TypeError || (error instanceof DOMException && error.name === 'NetworkError'))) throw error;
      }
      if (owner?.signal.aborted || this.currentAbortController !== owner) return;
      if (!this.reconnectable || !this.currentProcessId || attempt >= 3 || owner?.signal.aborted
          || this.currentAbortController !== owner) throw new Error('Connection lost. Reconnect the saved run; it was not restarted.');
      await new Promise(resolve => setTimeout(resolve, 200 * (attempt + 1)));
      if (owner?.signal.aborted || this.currentAbortController !== owner) return;
      response = await this.fetchReplay(this.currentProcessId);
    }
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
    if (data.turnId !== this.liveTurnId + 1) throw new Error('Missing live turn boundary');
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
    if ((action === 'input' || action === 'subagent_input') && (!text?.trim() || text.trimStart().startsWith('/'))) 
      throw new Error('Send slash commands after the live run finishes');
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
    if (action === 'input' && result.ok && text) this.liveInputHistory.push(text);
    return result;
  }

  private closeHarnessControls(): void {
    this.liveControlsReady = false;
    if (this.harnessActivity) {
      const terminalView = (entry: HarnessActivityEntry): HarnessActivityEntry =>
        ['RUNNING', 'BACKGROUNDED'].includes(entry.state) ? { ...entry, state: 'UNKNOWN (run ended)' } : entry;
      this.harnessActivity = { ...this.harnessActivity, backgroundable: false, turnActive: false,
        processes: this.harnessActivity.processes.map(terminalView), tasks: this.harnessActivity.tasks.map(terminalView),
        subagents: this.harnessActivity.subagents?.map(child => ({ ...child,
          state: child.running ? 'UNKNOWN (run ended)' : child.state, running: false, canSend: false, canCancel: false })) };
    }
    for (const pending of this.pendingControls.values()) {
      clearTimeout(pending.timer);
      pending.reject(new Error('Run ended before control acknowledgement; check retained activity'));
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
    // shows a live cursor); once answer text follows, close it.
    return this.getCurrentContent().length === 0
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
    try {
      console.debug('[LocalAgentChat] Starting stream request');

      // Create AbortController for cancellation support
      this.currentAbortController = new AbortController();
      this.currentProcessId = null;
      this.lastEventId = 0;
      this.reconnectable = false;
      this.browserSessionId = request.sessionId || session.id;
      this.reconnectSeed = JSON.parse(JSON.stringify(session));
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
        signal: this.currentAbortController.signal
      });

      console.debug('[LocalAgentChat] Response status:', response.status);

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`HTTP ${response.status}: ${errorText}`);
      }

      await this.consumeWithReconnect(session, response);

    } catch (error: any) {
      // Handle AbortError gracefully (user cancelled)
      if (error.name === 'AbortError') {
        // The cancelStreaming method already handled the UI update
        console.debug('[LocalAgentChat] Request aborted by user');
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

      const emitContentUpdate = () => {
        const fullContent = this.getCurrentContent();
        this.streamingContentRaw$.next({ content: fullContent, epoch: this.contentEpoch });

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
                  this.liveControlsReady = true;
                }
                break;

              case 'control': {
                const pending = this.pendingControls.get(parsed.requestId);
                if (pending) {
                  clearTimeout(pending.timer);
                  this.pendingControls.delete(parsed.requestId);
                  pending.resolve(parsed as HarnessControlReply);
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
                  const message = this.currentStreamingMessage;
                  if (message) {
                    message.content = this.renderThinkingPrefix() + this.getCurrentContent();
                    this.storageService.updateSession(session);
                    emitContentUpdate();
                  }
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

              case 'tool_use':
                console.debug('[LocalAgentChat] Tool use:', parsed);
                this.toolUse$.next(parsed as ToolUseEvent);
                if (this.currentStreamingMessage) {
                  if (!this.currentStreamingMessage.toolUses) {
                    this.currentStreamingMessage.toolUses = [];
                  }
                  this.currentStreamingMessage.toolUses.push(parsed);
                }
                break;

              case 'tool_result':
                console.debug('[LocalAgentChat] Tool completed:', parsed);
                this.result$.next({
                  durationMs: parsed.durationMs || 0,
                  numTurns: 0,
                  isError: parsed.ok === false
                });
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

      while (true) {
        const { done, value } = await reader.read();

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
      }

      reader.releaseLock();
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
        this.currentStreamingMessage.content = data.content;
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
  private finalizeStreaming(): void {
    this.closeHarnessControls();
    this.isStreaming$.next(false);
    this.currentStreamingMessage = null;
    this.currentProcessId = null;
    this.currentAbortController = null;
  }

  /**
   * Cancel current streaming.
   * Aborts the fetch request and sends cancel signal to backend.
   */
  /** Leave the socket without cancelling owned work; the existing timeout still applies. */
  detachStreaming(): void {
    this.currentAbortController?.abort();
    this.finalizeStreaming();
  }

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

  getResult(): Observable<ResultEvent> {
    return this.result$.asObservable();
  }

  getFilesModified(): Observable<string[]> {
    return this.filesModified$.asObservable();
  }

  getSources(): Observable<RetrievedSource[]> {
    return this.sources$.asObservable();
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

  // Synchronous getters
  isCurrentlyStreaming(): boolean {
    return this.isStreaming$.value;
  }

  getCurrentStreamingContent(): string {
    return this.streamingContent$.value;
  }
}
