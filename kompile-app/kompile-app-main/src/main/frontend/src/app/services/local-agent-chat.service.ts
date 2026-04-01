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
import { AgentService } from './agent.service';
import {
  AgentProvider,
  LocalAgentSession,
  LocalAgentMessage,
  LocalAgentChatRequest,
  ChatTabState,
  ToolUseEvent,
  ResultEvent,
  RetrievedSource,
  createUserMessage,
  createAssistantMessage,
  createNewSession,
  ChatHistoryEntry
} from '../models/api-models';

/**
 * Token metrics from LLM streaming responses.
 */
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
  private streamingContentRaw$ = new Subject<string>();
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

  constructor(
    private http: HttpClient,
    private ngZone: NgZone,
    private storageService: ChatStorageService,
    private agentService: AgentService
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
    ).subscribe(content => {
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
    this.contentChunks = [];
    this.lastEmittedLength = 0;
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
      skipPermissions?: boolean;
      workingDirectory?: string;
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
      // Timeout (0 = no timeout, default 300 = 5 minutes)
      timeoutSeconds?: number;
    } = {}
  ): Promise<void> {
    // Create and store user message
    const userMessage = createUserMessage(session.id, message);
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
      agentName: agent.name,
      skipPermissions: options.skipPermissions ?? true,
      workingDirectory: options.workingDirectory,
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
      // Timeout configuration (0 = no timeout)
      timeoutSeconds: options.timeoutSeconds ?? 300
    };

    console.log('[LocalAgentChat] Sending request with RAG enabled:', request.enableRag, 'timeout:', request.timeoutSeconds, 's');

    // Start streaming
    await this.streamWithFetch(session, request);
  }

  /**
   * Build chat history from session messages.
   */
  private buildChatHistory(session: LocalAgentSession, maxMessages: number): ChatHistoryEntry[] {
    const messages = session.messages.slice(-maxMessages - 1, -1); // Exclude current streaming message
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
      console.log('[LocalAgentChat] Starting stream request');

      // Create AbortController for cancellation support
      this.currentAbortController = new AbortController();
      this.currentProcessId = null;

      const response = await fetch(`${this.backendUrl}/agents/chat/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream'
        },
        body: JSON.stringify(request),
        signal: this.currentAbortController.signal
      });

      console.log('[LocalAgentChat] Response status:', response.status);

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`HTTP ${response.status}: ${errorText}`);
      }

      const reader = response.body?.getReader();
      if (!reader) throw new Error('No response body');

      const decoder = new TextDecoder();
      let buffer = '';
      let currentEventType = 'message';

      const emitContentUpdate = () => {
        const fullContent = this.getCurrentContent();
        this.streamingContentRaw$.next(fullContent);

        // Update message in session
        if (this.currentStreamingMessage) {
          this.currentStreamingMessage.content = fullContent;
        }
      };

      const processLine = (line: string) => {
        if (line.startsWith('event:')) {
          currentEventType = line.substring(6).trim();
          return;
        }

        if (line.startsWith('data:')) {
          const data = line.substring(5).trim();
          if (!data) return;

          try {
            const parsed = JSON.parse(data);

            switch (currentEventType) {
              case 'start':
                // Capture processId from start event for cancellation
                if (parsed.processId) {
                  this.currentProcessId = parsed.processId;
                  console.log('[LocalAgentChat] Process started:', this.currentProcessId);
                }
                break;

              case 'chunk':
                if (typeof parsed === 'string') {
                  this.accumulateContent(parsed);
                  emitContentUpdate();
                }
                break;

              case 'cancelled':
                // Process was cancelled by user
                console.log('[LocalAgentChat] Process cancelled:', parsed);
                if (this.currentStreamingMessage) {
                  this.currentStreamingMessage.streaming = false;
                  this.currentStreamingMessage.content = this.getCurrentContent() + '\n\n[Stopped]';
                  this.currentStreamingMessage.latencyMs = Date.now() - this.streamStartTime;
                  this.storageService.updateSession(session);
                }
                this.isStreaming$.next(false);
                this.currentStreamingMessage = null;
                this.currentProcessId = null;
                return; // Exit early

              case 'tool_use':
                console.log('[LocalAgentChat] Tool use:', parsed);
                this.toolUse$.next(parsed as ToolUseEvent);
                if (this.currentStreamingMessage) {
                  if (!this.currentStreamingMessage.toolUses) {
                    this.currentStreamingMessage.toolUses = [];
                  }
                  this.currentStreamingMessage.toolUses.push(parsed);
                }
                break;

              case 'result':
                console.log('[LocalAgentChat] Result:', parsed);
                this.result$.next(parsed as ResultEvent);
                if (this.currentStreamingMessage) {
                  this.currentStreamingMessage.tokenCount = parsed.numTurns;
                }
                break;

              case 'files_modified':
                console.log('[LocalAgentChat] Files modified:', parsed);
                this.filesModified$.next(parsed as string[]);
                if (this.currentStreamingMessage) {
                  this.currentStreamingMessage.modifiedFiles = parsed;
                }
                break;

              case 'sources':
                console.log('[LocalAgentChat] Sources received:', parsed);
                const sources = parsed as RetrievedSource[];
                this.sources$.next(sources);
                if (this.currentStreamingMessage) {
                  this.currentStreamingMessage.sources = sources;
                }
                break;

              case 'stats':
                console.log('[LocalAgentChat] Stats received:', parsed);
                const stats = parsed as ChatStats;
                this.chatStats$.next(stats);
                if (this.currentStreamingMessage && stats.tokenMetrics) {
                  this.currentStreamingMessage.tokenMetrics = stats.tokenMetrics;
                }
                break;

              case 'complete':
                console.log('[LocalAgentChat] Complete message received');
                this.handleStreamComplete(session, parsed);
                break;

              case 'error':
                console.error('[LocalAgentChat] Error event:', parsed);
                this.handleStreamError(session, typeof parsed === 'string' ? parsed : JSON.stringify(parsed));
                break;

              default:
                if (typeof parsed === 'string') {
                  this.accumulateContent(parsed);
                  emitContentUpdate();
                }
            }

            currentEventType = 'message';
          } catch {
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

      // Finalize streaming
      this.finalizeStreaming(session);

    } catch (error: any) {
      // Handle AbortError gracefully (user cancelled)
      if (error.name === 'AbortError') {
        console.log('[LocalAgentChat] Stream aborted by user');
        // Don't call handleStreamError for user-initiated cancellation
        // The cancelStreaming method already handled the UI update
        return;
      }

      console.error('[LocalAgentChat] Stream error:', error);
      this.handleStreamError(session, error.message);
    } finally {
      // Clean up abort controller
      this.currentAbortController = null;
    }
  }

  /**
   * Handle stream completion.
   */
  private handleStreamComplete(session: LocalAgentSession, data: any): void {
    if (this.currentStreamingMessage) {
      this.currentStreamingMessage.streaming = false;
      this.currentStreamingMessage.latencyMs = Date.now() - this.streamStartTime;

      if (data.content) {
        this.currentStreamingMessage.content = data.content;
      }
      if (data.rawResponse) {
        this.currentStreamingMessage.rawResponse = data.rawResponse;
      }

      this.storageService.updateSession(session);
      this.streamingComplete$.next(this.currentStreamingMessage);
    }

    this.isStreaming$.next(false);
    this.currentStreamingMessage = null;
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

    this.streamingError$.next(errorMessage);
    this.isStreaming$.next(false);
    this.currentStreamingMessage = null;
  }

  /**
   * Finalize streaming if no complete event was received.
   */
  private finalizeStreaming(session: LocalAgentSession): void {
    if (this.currentStreamingMessage && this.currentStreamingMessage.streaming) {
      this.currentStreamingMessage.streaming = false;
      this.currentStreamingMessage.content = this.getCurrentContent();
      this.currentStreamingMessage.latencyMs = Date.now() - this.streamStartTime;

      this.storageService.updateSession(session);
      this.streamingComplete$.next(this.currentStreamingMessage);
    }

    this.streamingContent$.next(this.getCurrentContent());
    this.isStreaming$.next(false);
    this.currentStreamingMessage = null;
    this.currentProcessId = null;
    this.currentAbortController = null;
  }

  /**
   * Cancel current streaming.
   * Aborts the fetch request and sends cancel signal to backend.
   */
  cancelStreaming(): void {
    console.log('[LocalAgentChat] Cancel streaming requested');

    // Abort the fetch request
    if (this.currentAbortController) {
      console.log('[LocalAgentChat] Aborting fetch request');
      this.currentAbortController.abort();
      this.currentAbortController = null;
    }

    // Call backend to kill the process
    if (this.currentProcessId) {
      console.log('[LocalAgentChat] Sending cancel request to backend for process:', this.currentProcessId);
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
      console.log('[LocalAgentChat] Backend cancel result:', result);
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
      const role = msg.role === 'USER' ? 'You' : (msg.agent?.displayName || 'Assistant');
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
    const role = message.role === 'USER' ? 'You' : (message.agent?.displayName || 'Assistant');
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

  // Synchronous getters
  isCurrentlyStreaming(): boolean {
    return this.isStreaming$.value;
  }

  getCurrentStreamingContent(): string {
    return this.streamingContent$.value;
  }
}
