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

import { Component, OnInit, OnDestroy, ViewChild, ElementRef, AfterViewChecked, AfterViewInit, ChangeDetectionStrategy, ChangeDetectorRef, NgZone, HostListener } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { HttpClient } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { Subscription, fromEvent } from 'rxjs';
import { throttleTime, takeUntil, filter } from 'rxjs/operators';
import { Subject } from 'rxjs';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';

// Services
import { ConversationalRagService } from '../../services/conversational-rag.service';
import { LocalAgentChatService } from '../../services/local-agent-chat.service';
import { AgentService } from '../../services/agent.service';
import { ChatStorageService } from '../../services/chat-storage.service';
import { ChatHistoryService, ChatMessageDto } from '../../services/chat-history.service';
import { CliTranscriptService, CliSessionSummary, CliTranscriptDetail, CliSourceInfo, ChatSyncStatus } from '../../services/cli-transcript.service';
import { FolderService } from '../../services/folder.service';
import { ModelContextService } from '../../services/model-context.service';
import { WebSocketService } from '../../services/websocket.service';
import { SystemPromptService } from '../../services/system-prompt.service';
import { SystemPrompt } from '../../models/system-prompt.models';
import { MarkdownRendererService, MessageSegment } from '../../services/markdown-renderer.service';
import { MonitorEvent } from '../../models/monitor-models';

// Models
import {
  ConversationalRagOptions,
  RetrievedDocument,
  SearchType,
  DEFAULT_RAG_OPTIONS,
  RerankerConfig,
  RerankerType,
  DEFAULT_RERANKER_CONFIG,
  RERANKER_TYPES,
  RerankerTypeInfo,
  AgentProvider,
  ApiAgentConfigRequest,
  LocalAgentSession,
  RagServiceStatus,
  ChatFolder,
  KompileLocalModelStatus,
  ActiveModelContext,
  MessageAttachment
} from '../../models/api-models';

// Unified message interface
interface UnifiedMessage {
  id: string;                      // Client-side ID for React-style tracking
  dbId?: number;                   // Database ID for backend operations (0 or undefined = not saved)
  role: 'user' | 'assistant' | 'system';
  content: string;
  timestamp: Date;
  isStreaming?: boolean;
  error?: boolean;

  // RAG-specific fields
  documents?: RetrievedDocument[];
  documentsExpanded?: boolean;
  metrics?: {
    totalMs: number;
    retrievalMs: number;
    generationMs: number;
    documentsRetrieved: number;
  };
  ragMetrics?: {
    retrievalMs: number;
    documentsRetrieved: number;
  };
  queryInfo?: {
    originalQuery: string;
    rewrittenQuery: string;
    wasRewritten: boolean;
    intent?: string;
    expanded?: boolean;
  };

  // Agent-specific fields
  agent?: AgentProvider;
  sources?: any[];
  _sourcesExpanded?: boolean;
  latencyMs?: number;
  tokenCount?: number;
  attachments?: MessageAttachment[];
  tokenMetrics?: {
    outputTokens: number;
    inputTokens: number;
    totalGenerationMs: number;
    tokensPerSecond: number;
    model?: string;
  };
}

// Session for persistence
interface ChatSession {
  id: string;
  name: string;
  messages: UnifiedMessage[];
  createdAt: string;
  updatedAt: string;
  archived?: boolean;
  agentName?: string;
  conversationId?: string; // For RAG conversation tracking
  source?: string; // Source badge: kompile, claude-code, opencode, codex, qwen
  synced?: boolean; // True if loaded from backend (not localStorage)
  messageCount?: number; // Message count from backend (avoids loading full messages)
}

@Component({
  standalone: false,
  selector: 'app-unified-chat',
  templateUrl: './unified-chat.component.html',
  styleUrls: ['./unified-chat.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class UnifiedChatComponent implements OnInit, OnDestroy, AfterViewChecked, AfterViewInit {
  @ViewChild('conversationArea') private conversationArea!: ElementRef;

  // Destroy subject for cleanup
  private destroy$ = new Subject<void>();

  // ═══════════════════════════════════════════════════════════════════════════════
  // VIEWPORT-BASED LOADING (from campaign-builder)
  // ═══════════════════════════════════════════════════════════════════════════════

  // Large message threshold - messages above this are handled specially
  readonly LARGE_MESSAGE_THRESHOLD = 3000; // Characters

  // Maximum characters to keep in DOM
  readonly MAX_DOM_CHARS = 50000;

  // Preload threshold - start loading when within this many pixels of edge
  readonly PRELOAD_THRESHOLD = 400;

  // Loading state indicators
  isLoadingMore = false;
  hasMoreAbove = false;
  hasMoreBelow = false;

  // Track scroll state
  private lastScrollTop = 0;
  private scrollDirection: 'up' | 'down' | 'none' = 'none';

  // Change detection optimization
  private isDetached = false;
  private streamingUpdateInterval: any = null;

  // Expose Object for template use
  Object = Object;

  // ═══════════════════════════════════════════════════════════════════════════════
  // CORE STATE
  // ═══════════════════════════════════════════════════════════════════════════════

  // Messages and sessions
  messages: UnifiedMessage[] = [];
  sessions: ChatSession[] = [];
  currentSession: ChatSession | null = null;

  // Input
  userInput: string = '';
  isLoading: boolean = false;
  isStreaming: boolean = false;

  // ═══════════════════════════════════════════════════════════════════════════════
  // UI STATE
  // ═══════════════════════════════════════════════════════════════════════════════

  showSettings: boolean = false;
  showHistorySidebar: boolean = true;
  chatSearchQuery: string = '';
  editingSessionId: string | null = null;
  editingSessionName: string = '';
  showArchivedChats: boolean = false;

  // Scroll
  private shouldScrollToBottom: boolean = false;

  // ═══════════════════════════════════════════════════════════════════════════════
  // RAG CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════════

  serviceAvailable: boolean = false;
  serviceName: string = '';
  currentConversationId: string | null = null;

  searchType: SearchType = 'hybrid';
  semanticK: number = DEFAULT_RAG_OPTIONS.semanticK || 5;
  keywordK: number = DEFAULT_RAG_OPTIONS.keywordK || 5;
  similarityThreshold: number = DEFAULT_RAG_OPTIONS.similarityThreshold || 0.5;
  maxHistoryMessages: number = DEFAULT_RAG_OPTIONS.maxHistoryMessages || 10;
  useToolCalling: boolean = DEFAULT_RAG_OPTIONS.useToolCalling || false;
  enableQueryProcessing: boolean = DEFAULT_RAG_OPTIONS.enableQueryProcessing || true;
  systemPrompt: string = '';
  useStreaming: boolean = true;

  // System prompt picker state
  availableSystemPrompts: SystemPrompt[] = [];
  activeSystemPrompt: SystemPrompt | null = null;
  systemPromptMode: 'active' | 'custom' = 'active';
  showPromptContent: boolean = false;

  // Reranking
  rerankerTypes: RerankerTypeInfo[] = RERANKER_TYPES;
  rerankerEnabled: boolean = DEFAULT_RERANKER_CONFIG.enabled;
  rerankerType: RerankerType = DEFAULT_RERANKER_CONFIG.type;
  rerankerFbDocs: number = DEFAULT_RERANKER_CONFIG.fbDocs;
  rerankerFbTerms: number = DEFAULT_RERANKER_CONFIG.fbTerms;
  rerankerTopK: number = DEFAULT_RERANKER_CONFIG.topK || -1;
  // RM3 parameters
  rerankerOriginalQueryWeight: number = DEFAULT_RERANKER_CONFIG.originalQueryWeight;
  rerankerFilterTerms: boolean = DEFAULT_RERANKER_CONFIG.filterTerms;
  rerankerOutputQuery: boolean = DEFAULT_RERANKER_CONFIG.outputQuery || false;
  // BM25-PRF parameters
  rerankerK1: number = DEFAULT_RERANKER_CONFIG.k1;
  rerankerB: number = DEFAULT_RERANKER_CONFIG.b;
  rerankerNewTermWeight: number = DEFAULT_RERANKER_CONFIG.newTermWeight;
  // Rocchio parameters
  rerankerAlpha: number = DEFAULT_RERANKER_CONFIG.alpha;
  rerankerBeta: number = DEFAULT_RERANKER_CONFIG.beta;
  rerankerGamma: number = DEFAULT_RERANKER_CONFIG.gamma;
  rerankerUseNegative: boolean = DEFAULT_RERANKER_CONFIG.useNegative;
  // Axiom parameters
  rerankerR: number = DEFAULT_RERANKER_CONFIG.r || 20;
  rerankerN: number = DEFAULT_RERANKER_CONFIG.n || 30;
  rerankerAxiomBeta: number = DEFAULT_RERANKER_CONFIG.axiomBeta || 0.4;
  rerankerDeterministic: boolean = DEFAULT_RERANKER_CONFIG.deterministic || true;
  rerankerSeed: number = DEFAULT_RERANKER_CONFIG.seed || 42;
  // Cross-encoder parameters
  rerankerCrossEncoderModel: string = DEFAULT_RERANKER_CONFIG.crossEncoderModel || '';
  crossEncoderModels: string[] = [];
  showAdvancedRerankerSettings: boolean = false;

  // Display toggles
  showDocuments: boolean = true;
  showMetrics: boolean = true;
  showQueryInfo: boolean = false;

  // ═══════════════════════════════════════════════════════════════════════════════
  // AGENT CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════════

  agents: AgentProvider[] = [];
  selectedAgent: AgentProvider | null = null;
  skipPermissions: boolean = true;
  agentsLoading: boolean = false;

  // API Agent configuration UI state
  showApiAgentConfig: boolean = false;
  apiAgentName: string = '';
  apiAgentDisplayName: string = '';
  apiAgentEndpointUrl: string = '';
  apiAgentApiKey: string = '';
  apiAgentModelName: string = '';
  apiAgentTemperature: number = 0.7;
  apiAgentMaxTokens: number = 4096;
  apiAgentTestResult: string = '';
  apiAgentTestLoading: boolean = false;
  apiAgentSaving: boolean = false;
  editingApiAgentName: string | null = null;

  // RAG settings (augments agent prompts with document context)
  ragEnabled: boolean = false;
  ragMaxResults: number = 5;
  ragThreshold: number = 0.0;

  // Graph RAG settings (knowledge graph-based retrieval)
  graphRagEnabled: boolean = false;
  graphRagSearchType: string = 'LOCAL';
  graphRagMaxResults: number = 5;

  // Timeout settings (0 = no timeout)
  timeoutSeconds: number = 300; // Default 5 minutes
  timeoutOptions: { label: string; value: number }[] = [
    { label: 'No timeout', value: 0 },
    { label: '1 minute', value: 60 },
    { label: '2 minutes', value: 120 },
    { label: '5 minutes', value: 300 },
    { label: '10 minutes', value: 600 },
    { label: '15 minutes', value: 900 },
    { label: '30 minutes', value: 1800 }
  ];

  // Agent session for chat
  private agentSession: LocalAgentSession | null = null;

  // Kompile Local Model state
  kompileLocalStatus: any = null;
  kompileLocalStagingUrl: string = 'http://localhost:8090';
  kompileLocalLoading: boolean = false;

  // Attachment state
  pendingAttachments: MessageAttachment[] = [];
  isDragOver: boolean = false;
  agentSupportsVision: boolean = false;

  // Active Model Context state
  activeModelContext: ActiveModelContext | null = null;
  modelContextLoading: boolean = false;
  embeddingSwitchLoading: boolean = false;
  showModelSwitchDropdown: boolean = false;

  // ═══════════════════════════════════════════════════════════════════════════════
  // FOLDER CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════════

  folders: ChatFolder[] = [];
  selectedFolder: ChatFolder | null = null;
  showFolderSidebar: boolean = false;
  foldersLoading: boolean = false;

  // ═══════════════════════════════════════════════════════════════════════════════
  // CLI TRANSCRIPT INTEGRATION
  // ═══════════════════════════════════════════════════════════════════════════════

  sidebarTab: 'app' | 'cli' = 'app';
  cliSessions: CliSessionSummary[] = [];
  cliSourceFilter: string = 'all';
  cliSources: { [source: string]: CliSourceInfo } = {};
  cliLoading: boolean = false;
  cliImporting: string | null = null; // sessionId being imported
  cliPreview: CliTranscriptDetail | null = null;
  cliPreviewLoading: boolean = false;

  // Unified sidebar: synced sessions from backend
  syncedSessions: ChatSession[] = [];
  sourceFilter: string = 'all'; // Filter for unified sidebar
  syncedSessionsLoading: boolean = false;
  chatSyncStatus: ChatSyncStatus | null = null;
  showSyncComplete: boolean = false;
  private syncPollTimer: any = null;

  // ═══════════════════════════════════════════════════════════════════════════════
  // SUBSCRIPTIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  private subscriptions: Subscription[] = [];
  private streamingSubscription: Subscription | null = null;
  private activeStreamingSubs: Subscription[] = [];

  // Monitor wake-up subscription (re-bound whenever currentSession changes)
  private monitorSubscription: Subscription | null = null;
  private monitorSubscribedSessionId: string | null = null;

  // Action UI state
  copiedIndex: number | null = null;
  isProcessing: boolean = false;

  constructor(
    private ragService: ConversationalRagService,
    private agentChatService: LocalAgentChatService,
    private agentService: AgentService,
    private storageService: ChatStorageService,
    private chatHistoryService: ChatHistoryService,
    private cliTranscriptService: CliTranscriptService,
    private folderService: FolderService,
    private modelContextService: ModelContextService,
    private webSocketService: WebSocketService,
    private systemPromptService: SystemPromptService,
    private markdownRenderer: MarkdownRendererService,
    private http: HttpClient,
    private cdr: ChangeDetectorRef,
    private ngZone: NgZone,
    private sanitizer: DomSanitizer,
    private dialog: MatDialog
  ) {}

  /**
   * Handle clicks on source reference links in message content
   */
  @HostListener('click', ['$event'])
  onContentClick(event: Event): void {
    const target = event.target as HTMLElement;
    if (target.classList.contains('source-ref-link')) {
      event.preventDefault();
      const messageId = target.getAttribute('data-message-id');
      const sourceIndex = parseInt(target.getAttribute('data-source-index') || '0', 10);

      // Find the message and scroll to source
      const message = this.messages.find(m => m.id === messageId);
      if (message) {
        this.scrollToSource(message, sourceIndex);
      }
    }
  }

  ngOnInit(): void {
    this.loadSessions();
    this.checkRagServiceStatus();
    this.loadAgents();
    this.loadFolders();
    this.loadKompileLocalStatus();
    this.initModelContext();
    this.loadSystemPrompts();

    // Subscribe to agent updates
    this.subscriptions.push(
      this.agentService.agents$.subscribe(agents => {
        this.agents = agents;
        this.cdr.markForCheck();
      })
    );

    // Subscribe to folder updates
    this.subscriptions.push(
      this.folderService.folders$.subscribe(folders => {
        this.folders = folders;
        this.cdr.markForCheck();
      })
    );

    // Subscribe to selected folder changes
    this.subscriptions.push(
      this.folderService.selectedFolder$.subscribe(folder => {
        this.selectedFolder = folder;
        this.cdr.markForCheck();
      })
    );
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach(sub => sub.unsubscribe());
    if (this.streamingSubscription) {
      this.streamingSubscription.unsubscribe();
    }
    this.teardownMonitorSubscription();
    this.stopSyncPolling();
    this.destroy$.next();
    this.destroy$.complete();

    // Clean up streaming interval
    if (this.streamingUpdateInterval) {
      clearInterval(this.streamingUpdateInterval);
    }

    // Ensure change detection is reattached
    if (this.isDetached) {
      this.cdr.reattach();
    }
  }

  ngAfterViewInit(): void {
    // Set up scroll listener for auto-loading older messages
    this.setupScrollListener();
  }

  /**
   * Ensure we are subscribed to /topic/monitor/{sessionId} for the currently
   * active chat session. Called after every session switch. When a monitor
   * fires (watched task completes, schedule triggers) we render a system-role
   * message inline in the chat so the user sees the wake-up without needing
   * to refresh.
   */
  private updateMonitorSubscription(): void {
    const sessionId = this.currentSession?.id ?? null;
    if (sessionId === this.monitorSubscribedSessionId) {
      return;
    }
    this.teardownMonitorSubscription();
    if (!sessionId) {
      return;
    }
    this.webSocketService.connect();
    this.monitorSubscribedSessionId = sessionId;
    this.monitorSubscription = this.webSocketService.subscribeToMonitor(sessionId)
      .subscribe((event: MonitorEvent) => {
        this.handleMonitorEvent(event);
      });
  }

  private teardownMonitorSubscription(): void {
    if (this.monitorSubscription) {
      this.monitorSubscription.unsubscribe();
      this.monitorSubscription = null;
    }
    if (this.monitorSubscribedSessionId) {
      this.webSocketService.unsubscribeFromMonitor(this.monitorSubscribedSessionId);
      this.monitorSubscribedSessionId = null;
    }
  }

  private handleMonitorEvent(event: MonitorEvent): void {
    const prefix = event.success ? '🔔' : '⚠️';
    const body = event.payload ? `${event.message}\n\n${event.payload}` : event.message;
    const systemMsg: UnifiedMessage = {
      id: 'monitor-' + event.monitorId + '-' + Date.now(),
      role: 'system',
      content: `${prefix} **${event.title}**\n\n${body}`,
      timestamp: event.firedAt ? new Date(event.firedAt) : new Date()
    };
    this.messages.push(systemMsg);
    if (this.currentSession) {
      this.currentSession.messages = [...this.messages];
      this.currentSession.updatedAt = new Date().toISOString();
      this.saveSessions();
    }
    this.shouldScrollToBottom = true;
    this.cdr.markForCheck();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SCROLL MANAGEMENT (from campaign-builder)
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Set up scroll listener for loading older messages when scrolling to top.
   * Runs outside Angular zone to prevent change detection on every scroll.
   */
  private setupScrollListener(): void {
    if (!this.conversationArea) return;

    // Run outside Angular zone to prevent change detection on every scroll
    this.ngZone.runOutsideAngular(() => {
      fromEvent(this.conversationArea.nativeElement, 'scroll')
        .pipe(
          throttleTime(100), // Check frequently for responsive scrolling
          takeUntil(this.destroy$)
        )
        .subscribe(() => {
          this.checkAndLoadOlderMessages();
        });
    });

    // Also check immediately and after a short delay in case we start at top
    setTimeout(() => this.checkAndLoadOlderMessages(), 100);
    setTimeout(() => this.checkAndLoadOlderMessages(), 500);
  }

  /**
   * Check if we should load older messages and do so if needed.
   */
  private checkAndLoadOlderMessages(): void {
    if (!this.conversationArea) return;

    const el = this.conversationArea.nativeElement;
    const scrollTop = el.scrollTop;
    const scrollHeight = el.scrollHeight;
    const clientHeight = el.clientHeight;

    // Track scroll direction
    if (scrollTop < this.lastScrollTop) {
      this.scrollDirection = 'up';
    } else if (scrollTop > this.lastScrollTop) {
      this.scrollDirection = 'down';
    }
    this.lastScrollTop = scrollTop;

    // Load more older messages when near the top
    const nearTop = scrollTop < this.PRELOAD_THRESHOLD;
    const contentDoesntFillViewport = scrollHeight <= clientHeight + 50;

    if ((nearTop || contentDoesntFillViewport) && this.hasMoreAbove && !this.isLoadingMore) {
      this.ngZone.run(() => {
        this.loadOlderMessages();
      });
    }
  }

  /**
   * Load older messages and prepend them to the display buffer.
   * Maintains scroll position so user doesn't jump around.
   */
  private loadOlderMessages(): void {
    // Placeholder for loading older messages from backend
    // This would typically call a service method to fetch paginated messages
    // For now, we just mark that we're not loading and there's nothing more above
    this.isLoadingMore = false;
    this.hasMoreAbove = false;
    this.cdr.markForCheck();
  }

  /**
   * Check if a message is considered "large" based on content length.
   */
  isLargeMessage(message: UnifiedMessage): boolean {
    return message.content.length > this.LARGE_MESSAGE_THRESHOLD;
  }

  ngAfterViewChecked(): void {
    if (this.shouldScrollToBottom) {
      this.scrollToBottom();
      this.shouldScrollToBottom = false;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SESSION MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  private loadSessions(): void {
    const stored = localStorage.getItem('unified_chat_sessions');
    if (stored) {
      try {
        this.sessions = JSON.parse(stored);
      } catch (e) {
        this.sessions = [];
      }
    }

    // Also load synced sessions from backend
    this.loadSyncedSessions();
  }

  loadSyncedSessions(): void {
    this.syncedSessionsLoading = true;
    this.chatHistoryService.getSessions().subscribe({
      next: (backendSessions) => {
        this.syncedSessions = backendSessions
          .filter(s => s.source) // Only sessions with a source (synced from CLI)
          .map(s => ({
            id: s.sessionId,
            name: s.title || 'Imported Chat',
            messages: [],
            createdAt: s.createdAt,
            updatedAt: s.updatedAt,
            source: s.source,
            synced: true,
            messageCount: s.messageCount
          }));
        this.syncedSessionsLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.syncedSessions = [];
        this.syncedSessionsLoading = false;
        this.cdr.markForCheck();
      }
    });

    // Also check current sync status
    this.pollSyncStatus();
  }

  triggerChatSync(): void {
    this.cliTranscriptService.triggerSync().subscribe({
      next: (result) => {
        if (result.triggered) {
          this.startSyncPolling();
        } else {
          // Already running, just start polling to show progress
          this.startSyncPolling();
        }
        this.cdr.markForCheck();
      },
      error: () => {
        // Fallback: just refresh
        this.loadSyncedSessions();
      }
    });
  }

  private startSyncPolling(): void {
    if (this.syncPollTimer) return;
    this.pollSyncStatus();
    this.syncPollTimer = setInterval(() => this.pollSyncStatus(), 2000);
  }

  private stopSyncPolling(): void {
    if (this.syncPollTimer) {
      clearInterval(this.syncPollTimer);
      this.syncPollTimer = null;
    }
  }

  private pollSyncStatus(): void {
    this.cliTranscriptService.getSyncStatus().subscribe({
      next: (status) => {
        const wasRunning = this.chatSyncStatus?.running;
        this.chatSyncStatus = status;

        if (status.running && !this.syncPollTimer) {
          // Sync is running (e.g. initial startup sync), start polling
          this.startSyncPolling();
        }

        if (wasRunning && !status.running) {
          // Sync just finished — refresh the session list and show completion
          this.stopSyncPolling();
          this.loadSyncedSessionsQuiet();
          this.showSyncComplete = true;
          setTimeout(() => {
            this.showSyncComplete = false;
            this.cdr.markForCheck();
          }, 5000);
        }

        this.cdr.markForCheck();
      },
      error: () => {
        this.stopSyncPolling();
      }
    });
  }

  private loadSyncedSessionsQuiet(): void {
    this.chatHistoryService.getSessions().subscribe({
      next: (backendSessions) => {
        this.syncedSessions = backendSessions
          .filter(s => s.source)
          .map(s => ({
            id: s.sessionId,
            name: s.title || 'Imported Chat',
            messages: [],
            createdAt: s.createdAt,
            updatedAt: s.updatedAt,
            source: s.source,
            synced: true,
            messageCount: s.messageCount
          }));
        this.cdr.markForCheck();
      },
      error: () => {}
    });
  }

  /**
   * Map an agentName (from cli-agents.json) to the corresponding source filter value
   * used for synced CLI transcript sessions.
   */
  private agentNameToSource(agentName: string | undefined): string | undefined {
    if (!agentName) return undefined;
    const map: { [key: string]: string } = {
      'claude-cli': 'claude-code',
      'codex-cli': 'codex',
      'opencode-cli': 'opencode',
      'qwen-cli': 'qwen',
      'gemini-cli': 'gemini',
      'pi-cli': 'pi',
      'kompile': 'kompile'
    };
    return map[agentName] || agentName;
  }

  getAllSessions(): ChatSession[] {
    // Merge local sessions (with messages) with synced sessions
    let local = this.sessions.filter(s => s.messages && s.messages.length > 0);
    let all = [...local, ...this.syncedSessions];

    // Source filter — match both synced session source AND local session agentName
    if (this.sourceFilter && this.sourceFilter !== 'all') {
      if (this.sourceFilter === 'app') {
        all = all.filter(s => !s.source && !s.agentName);
      } else {
        all = all.filter(s =>
          s.source === this.sourceFilter ||
          this.agentNameToSource(s.agentName) === this.sourceFilter
        );
      }
    }

    // Archived filter
    if (!this.showArchivedChats) {
      all = all.filter(s => !s.archived);
    }

    // Search filter
    if (this.chatSearchQuery.trim()) {
      const query = this.chatSearchQuery.toLowerCase();
      all = all.filter(s =>
        (s.name || '').toLowerCase().includes(query) ||
        (s.messages && s.messages.some(m => m.content.toLowerCase().includes(query)))
      );
    }

    // Exclude current session
    if (this.currentSession) {
      all = all.filter(s => s.id !== this.currentSession!.id);
    }

    // Sort by updatedAt descending
    all.sort((a, b) => {
      const dateA = new Date(a.updatedAt).getTime();
      const dateB = new Date(b.updatedAt).getTime();
      return dateB - dateA;
    });

    return all;
  }

  sessionMatchesSourceFilter(session: ChatSession): boolean {
    if (this.sourceFilter === 'all') return true;
    if (this.sourceFilter === 'app') return !session.source && !session.agentName;
    return session.source === this.sourceFilter ||
      this.agentNameToSource(session.agentName) === this.sourceFilter;
  }

  onSourceFilterChange(source: string): void {
    this.sourceFilter = source;
    this.cdr.markForCheck();
  }

  loadSyncedSession(session: ChatSession): void {
    if (!session.synced) {
      this.loadSession(session);
      return;
    }

    // Load messages from backend for synced sessions
    this.chatHistoryService.getSessionMessages(session.id).subscribe({
      next: (msgs) => {
        const messages: UnifiedMessage[] = msgs.map(m => ({
          id: 'synced-' + (m.id || Math.random().toString(36).substring(2)),
          dbId: m.id,
          role: m.role === 'USER' ? 'user' as const : m.role === 'ASSISTANT' ? 'assistant' as const : 'system' as const,
          content: m.content,
          timestamp: new Date(m.createdAt || Date.now())
        }));

        session.messages = messages;
        this.currentSession = session;
        this.messages = messages;
        this.shouldScrollToBottom = true;
        this.updateMonitorSubscription();
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load synced session:', err);
      }
    });
  }

  private saveSessions(): void {
    localStorage.setItem('unified_chat_sessions', JSON.stringify(this.sessions));
  }

  newChat(): void {
    const session: ChatSession = {
      id: this.generateId(),
      name: 'New Chat',
      messages: [],
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      agentName: this.selectedAgent?.name
    };

    this.sessions.unshift(session);
    this.currentSession = session;
    this.messages = [];
    this.currentConversationId = null;
    this.agentSession = null; // Reset agent session for new chat
    this.saveSessions();
    this.updateMonitorSubscription();
    this.cdr.detectChanges();
  }

  loadSession(session: ChatSession): void {
    this.currentSession = session;
    this.messages = [...session.messages];
    this.currentConversationId = session.conversationId || null;

    if (session.agentName) {
      this.selectedAgent = this.agents.find(a => a.name === session.agentName) || null;
    }

    // Reset agent session when loading a different session
    this.agentSession = null;
    this.shouldScrollToBottom = true;
    this.updateMonitorSubscription();
  }

  deleteSession(session: ChatSession): void {
    const dialogData: ConfirmDialogData = {
      title: 'Delete Session',
      message: `Are you sure you want to delete "${session.name}"?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(filter(confirmed => confirmed === true))
      .subscribe(() => {
        this.sessions = this.sessions.filter(s => s.id !== session.id);
        if (this.currentSession?.id === session.id) {
          this.currentSession = null;
          this.messages = [];
          this.updateMonitorSubscription();
        }
        this.saveSessions();
        this.cdr.detectChanges();
      });
  }

  archiveSession(session: ChatSession): void {
    session.archived = !session.archived;
    session.updatedAt = new Date().toISOString();
    this.saveSessions();
  }

  /**
   * Add a chat session to a folder.
   * Uses the session ID to associate with the folder via the backend.
   */
  addSessionToFolder(session: ChatSession, folder: ChatFolder): void {
    this.folderService.associateSession(folder.folderId, session.id).subscribe({
      next: () => {
        // Refresh folders to update session counts
        this.loadFolders();
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to add session to folder:', err);
      }
    });
  }

  /**
   * Remove a chat session from its current folder.
   */
  removeSessionFromFolder(session: ChatSession, folder: ChatFolder): void {
    this.folderService.disassociateSession(folder.folderId, session.id).subscribe({
      next: () => {
        // Refresh folders to update session counts
        this.loadFolders();
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to remove session from folder:', err);
      }
    });
  }

  startEditSessionName(session: ChatSession): void {
    this.editingSessionId = session.id;
    this.editingSessionName = session.name;
    setTimeout(() => {
      const input = document.querySelector('.chat-name-input') as HTMLInputElement;
      if (input) {
        input.focus();
        input.select();
      }
    }, 50);
  }

  saveSessionName(session: ChatSession): void {
    if (this.editingSessionName.trim()) {
      session.name = this.editingSessionName.trim();
      session.updatedAt = new Date().toISOString();
      this.saveSessions();
    }
    this.cancelEditSessionName();
  }

  cancelEditSessionName(): void {
    this.editingSessionId = null;
    this.editingSessionName = '';
  }

  getFilteredSessions(): ChatSession[] {
    let filtered = this.sessions.filter(s => s.messages.length > 0);

    if (!this.showArchivedChats) {
      filtered = filtered.filter(s => !s.archived);
    }

    if (this.chatSearchQuery.trim()) {
      const query = this.chatSearchQuery.toLowerCase();
      filtered = filtered.filter(s =>
        s.name.toLowerCase().includes(query) ||
        s.messages.some(m => m.content.toLowerCase().includes(query))
      );
    }

    return filtered.sort((a, b) =>
      new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
    );
  }

  getSessionPreview(session: ChatSession): string {
    const firstUserMsg = session.messages.find(m => m.role === 'user');
    if (!firstUserMsg) return '';
    const content = firstUserMsg.content;
    return content.length > 50 ? content.substring(0, 47) + '...' : content;
  }

  formatSessionDate(dateString: string): string {
    const date = new Date(dateString);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));

    if (diffDays === 0) {
      return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } else if (diffDays === 1) {
      return 'Yesterday';
    } else if (diffDays < 7) {
      return `${diffDays}d ago`;
    } else {
      return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // MESSAGING
  // ═══════════════════════════════════════════════════════════════════════════════

  sendMessage(): void {
    if (!this.userInput.trim() || this.isLoading || this.isStreaming) return;

    const content = this.userInput.trim();
    this.userInput = '';

    // Capture and clear pending attachments
    const attachments = this.pendingAttachments.length > 0 ? [...this.pendingAttachments] : undefined;
    this.pendingAttachments = [];

    // Create session if needed
    if (!this.currentSession) {
      this.newChat();
    }

    // Add user message
    const userMessage: UnifiedMessage = {
      id: this.generateId(),
      role: 'user',
      content: content,
      timestamp: new Date(),
      attachments: attachments
    };

    this.messages.push(userMessage);
    this.updateCurrentSession();
    this.shouldScrollToBottom = true;
    this.cdr.detectChanges();

    // Send to agent (with optional RAG augmentation)
    this.sendAgentMessage(content, attachments);
  }

  private async sendAgentMessage(content: string, attachments?: MessageAttachment[]): Promise<void> {
    if (!this.selectedAgent) return;

    this.isStreaming = true;

    // Create a session for agent chat if needed
    if (!this.agentSession) {
      this.agentSession = this.agentChatService.createSession(
        this.currentSession?.name || 'Chat',
        this.selectedAgent
      );
    }

    const startTime = Date.now();

    // Detach change detection during streaming for performance (campaign-builder pattern)
    this.cdr.detach();
    this.isDetached = true;

    // Set up interval to update UI during streaming
    this.streamingUpdateInterval = setInterval(() => {
      if (this.isStreaming) {
        this.cdr.detectChanges();
      }
    }, 300); // Update UI every 300ms during streaming

    // Clean up any stale streaming subs from a previous run
    this.unsubscribeStreamingSubs();

    // Subscribe to streaming content updates
    const contentSub = this.agentChatService.getStreamingContent().subscribe((content: string) => {
      // Update the last assistant message
      const lastMsg = this.messages[this.messages.length - 1];
      if (lastMsg && lastMsg.role === 'assistant' && lastMsg.isStreaming) {
        lastMsg.content = content;
        this.shouldScrollToBottom = true;
      }
    });

    // Subscribe to chat stats (token metrics)
    const statsSub = this.agentChatService.getChatStats().subscribe((stats) => {
      const lastMsg = this.messages[this.messages.length - 1];
      if (lastMsg && lastMsg.role === 'assistant' && stats.tokenMetrics) {
        lastMsg.tokenMetrics = stats.tokenMetrics;
      }
    });

    const completeSub = this.agentChatService.getStreamingComplete().subscribe((msg) => {
      // Update last message with final content
      const lastMsg = this.messages[this.messages.length - 1];
      if (lastMsg && lastMsg.role === 'assistant') {
        lastMsg.content = msg.content;
        lastMsg.isStreaming = false;
        lastMsg.latencyMs = msg.latencyMs || (Date.now() - startTime);
        lastMsg.sources = msg.sources;
        if (msg.tokenMetrics) {
          lastMsg.tokenMetrics = msg.tokenMetrics;
        }
        if (msg.ragMetrics) {
          lastMsg.ragMetrics = msg.ragMetrics;
        }
        if (msg.queryInfo) {
          lastMsg.queryInfo = msg.queryInfo;
        }
      }
      this.isStreaming = false;

      // Clean up and reattach change detection
      this.cleanupStreaming();

      this.updateCurrentSession();
      this.unsubscribeStreamingSubs();
    });

    const errorSub = this.agentChatService.getStreamingError().subscribe((errorMsg: string) => {
      const lastMsg = this.messages[this.messages.length - 1];
      if (lastMsg && lastMsg.role === 'assistant') {
        lastMsg.error = true;
        lastMsg.content = errorMsg || 'Agent request failed';
        lastMsg.isStreaming = false;
      }
      this.isStreaming = false;

      // Clean up and reattach change detection
      this.cleanupStreaming();

      this.unsubscribeStreamingSubs();
    });

    // Store subs so cancelStreaming() can clean them up
    this.activeStreamingSubs = [contentSub, completeSub, errorSub, statsSub];

    // Add placeholder assistant message
    const assistantMessage: UnifiedMessage = {
      id: this.generateId(),
      role: 'assistant',
      content: '',
      timestamp: new Date(),
      isStreaming: true,
      agent: this.selectedAgent
    };
    this.messages.push(assistantMessage);

    // Send the message using LocalAgentChatService
    try {
      await this.agentChatService.sendMessage(
        this.agentSession,
        content,
        this.selectedAgent,
        {
          skipPermissions: this.skipPermissions,
          enableRag: this.ragEnabled,
          ragMaxResults: this.ragMaxResults,
          ragSimilarityThreshold: this.ragThreshold,
          enableGraphRag: this.graphRagEnabled,
          graphRagMaxResults: this.graphRagMaxResults,
          graphRagSearchType: this.graphRagSearchType,
          graphRagConversationId: this.currentSession?.conversationId || this.currentSession?.id,
          folderId: this.selectedFolder?.folderId,
          timeoutSeconds: this.timeoutSeconds,
          attachments: attachments
        }
      );
    } catch (error: unknown) {
      assistantMessage.error = true;
      assistantMessage.content = error instanceof Error ? error.message : 'Agent request failed';
      assistantMessage.isStreaming = false;
      this.isStreaming = false;

      // Clean up and reattach change detection
      this.cleanupStreaming();
      this.unsubscribeStreamingSubs();
    }
  }

  /**
   * Clean up streaming state and reattach change detection.
   * Called when streaming completes, errors, or is cancelled.
   */
  private unsubscribeStreamingSubs(): void {
    for (const sub of this.activeStreamingSubs) {
      sub.unsubscribe();
    }
    this.activeStreamingSubs = [];
  }

  private cleanupStreaming(): void {
    // Clear streaming interval
    if (this.streamingUpdateInterval) {
      clearInterval(this.streamingUpdateInterval);
      this.streamingUpdateInterval = null;
    }

    // Reattach change detection
    if (this.isDetached) {
      this.cdr.reattach();
      this.isDetached = false;
    }

    // Force final update
    this.cdr.detectChanges();
  }

  private handleError(errorMessage: string): void {
    const errorMsg: UnifiedMessage = {
      id: this.generateId(),
      role: 'assistant',
      content: errorMessage,
      timestamp: new Date(),
      error: true
    };
    this.messages.push(errorMsg);
    this.updateCurrentSession();
    this.shouldScrollToBottom = true;
  }

  private updateCurrentSession(): void {
    if (this.currentSession) {
      this.currentSession.messages = [...this.messages];
      this.currentSession.updatedAt = new Date().toISOString();

      // Auto-name session based on first user message
      if (this.currentSession.name === 'New Chat' && this.messages.length > 0) {
        const firstUserMsg = this.messages.find(m => m.role === 'user');
        if (firstUserMsg) {
          const name = firstUserMsg.content.substring(0, 30);
          this.currentSession.name = name + (firstUserMsg.content.length > 30 ? '...' : '');
        }
      }

      this.saveSessions();
    }
  }

  cancelStreaming(): void {
    console.log('[UnifiedChat] Cancel streaming requested');

    // Cancel via the service (this aborts fetch and kills backend process)
    this.agentChatService.cancelStreaming();

    if (this.streamingSubscription) {
      this.streamingSubscription.unsubscribe();
      this.streamingSubscription = null;
    }

    // Unsubscribe the per-stream content/complete/error/stats subs
    this.unsubscribeStreamingSubs();

    this.isStreaming = false;
    this.isLoading = false;

    // Mark the last message as not streaming
    if (this.messages.length > 0) {
      const lastMsg = this.messages[this.messages.length - 1];
      if (lastMsg.isStreaming) {
        lastMsg.isStreaming = false;
        // The service already appends [Stopped], so we just ensure streaming is false
        if (!lastMsg.content.includes('[Stopped]')) {
          lastMsg.content += ' [Stopped]';
        }
      }
    }

    // Update session with stopped message
    this.updateCurrentSession();

    // Clean up and reattach change detection
    this.cleanupStreaming();
  }

  clearConversation(): void {
    if (this.messages.length === 0) return;

    const dialogData: ConfirmDialogData = {
      title: 'Clear Conversation',
      message: 'Are you sure you want to clear this conversation? This cannot be undone.',
      confirmText: 'Clear',
      confirmColor: 'warn',
      icon: 'delete_forever'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(filter(confirmed => confirmed === true))
      .subscribe(() => {
        this.messages = [];
        this.currentConversationId = null;
        if (this.currentSession) {
          this.currentSession.messages = [];
          this.saveSessions();
        }
        this.cdr.detectChanges();
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SYSTEM PROMPTS
  // ═══════════════════════════════════════════════════════════════════════════════

  loadSystemPrompts(): void {
    this.systemPromptService.listPrompts().subscribe({
      next: (prompts) => {
        this.availableSystemPrompts = prompts;
        const active = prompts.find(p => p.isActive);
        if (active) {
          this.activeSystemPrompt = active;
          if (this.systemPromptMode === 'active') {
            this.systemPrompt = active.content;
          }
        }
        this.cdr.markForCheck();
      },
      error: () => {
        this.availableSystemPrompts = [];
      }
    });
  }

  onSystemPromptSelect(promptId: string): void {
    if (promptId === '__custom__') {
      this.systemPromptMode = 'custom';
      this.systemPrompt = '';
      this.activeSystemPrompt = null;
      this.showPromptContent = true;
      return;
    }
    if (promptId === '__none__') {
      this.systemPromptMode = 'active';
      this.systemPrompt = '';
      this.activeSystemPrompt = null;
      this.showPromptContent = false;
      return;
    }
    const selected = this.availableSystemPrompts.find(p => p.id === promptId);
    if (selected) {
      this.systemPromptMode = 'active';
      this.activeSystemPrompt = selected;
      this.systemPrompt = selected.content;
      this.showPromptContent = true;
    }
  }

  onActivateSystemPrompt(): void {
    if (!this.activeSystemPrompt) return;
    this.systemPromptService.activatePrompt(this.activeSystemPrompt.id).subscribe({
      next: (activated) => {
        this.availableSystemPrompts.forEach(p => {
          p.isActive = p.id === activated.id;
        });
        this.activeSystemPrompt = activated;
        this.cdr.markForCheck();
      },
      error: () => {}
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // RAG SERVICE
  // ═══════════════════════════════════════════════════════════════════════════════

  private checkRagServiceStatus(): void {
    this.ragService.getStatus().subscribe({
      next: (status: RagServiceStatus) => {
        this.serviceAvailable = status.available;
        this.serviceName = status.service || '';
      },
      error: () => {
        this.serviceAvailable = false;
      }
    });
  }

  private buildRagOptions(): ConversationalRagOptions {
    // Use the service's buildOptions helper for consistent options construction
    return this.ragService.buildOptions(
      this.searchType,
      this.semanticK,
      this.keywordK,
      this.similarityThreshold,
      this.maxHistoryMessages,
      this.useToolCalling,
      this.enableQueryProcessing,
      this.systemPrompt.trim() || undefined,
      this.rerankerEnabled ? this.buildRerankerConfig() : undefined
    );
  }

  private buildRerankerConfig(): RerankerConfig {
    return {
      enabled: this.rerankerEnabled,
      type: this.rerankerType,
      fbDocs: this.rerankerFbDocs,
      fbTerms: this.rerankerFbTerms,
      topK: this.rerankerTopK,
      // RM3 parameters
      originalQueryWeight: this.rerankerOriginalQueryWeight,
      filterTerms: this.rerankerFilterTerms,
      outputQuery: this.rerankerOutputQuery,
      // BM25-PRF parameters
      k1: this.rerankerK1,
      b: this.rerankerB,
      newTermWeight: this.rerankerNewTermWeight,
      // Rocchio parameters
      alpha: this.rerankerAlpha,
      beta: this.rerankerBeta,
      gamma: this.rerankerGamma,
      useNegative: this.rerankerUseNegative,
      // Axiom parameters
      r: this.rerankerR,
      n: this.rerankerN,
      axiomBeta: this.rerankerAxiomBeta,
      deterministic: this.rerankerDeterministic,
      seed: this.rerankerSeed,
      // Cross-encoder parameters
      crossEncoderModel: this.rerankerCrossEncoderModel
    };
  }

  /**
   * Get the icon for a reranker type.
   */
  getRerankerIcon(type: RerankerType): string {
    const icons: { [key: string]: string } = {
      'none': 'block',
      'rm3': 'auto_fix_high',
      'bm25prf': 'trending_up',
      'rocchio': 'scatter_plot',
      'axiom': 'hub',
      'score_ties': 'swap_vert',
      'cross_encoder': 'psychology',
      'rrf': 'merge_type',
      'normalize': 'straighten',
      'mmr': 'diversity_3'
    };
    return icons[type] || 'sort';
  }

  /**
   * Get the description for the current reranker type.
   */
  getRerankerDescription(): string {
    const type = this.rerankerTypes.find(t => t.id === this.rerankerType);
    return type?.description || '';
  }

  /**
   * Check if the current reranker type needs common parameters.
   */
  needsCommonParams(): boolean {
    return ['rm3', 'bm25prf', 'rocchio', 'axiom'].includes(this.rerankerType);
  }

  /**
   * Check if the current reranker type is RM3.
   */
  isRm3(): boolean {
    return this.rerankerType === 'rm3';
  }

  /**
   * Check if the current reranker type is BM25-PRF.
   */
  isBm25Prf(): boolean {
    return this.rerankerType === 'bm25prf';
  }

  /**
   * Check if the current reranker type is Rocchio.
   */
  isRocchio(): boolean {
    return this.rerankerType === 'rocchio';
  }

  /**
   * Check if the current reranker type is Axiom.
   */
  isAxiom(): boolean {
    return this.rerankerType === 'axiom';
  }

  /**
   * Check if the current reranker type is Cross-Encoder.
   */
  isCrossEncoder(): boolean {
    return this.rerankerType === 'cross_encoder';
  }

  /**
   * Check if the current reranker type is RRF.
   */
  isRrf(): boolean {
    return this.rerankerType === 'rrf';
  }

  /**
   * Check if the current reranker type is Normalize.
   */
  isNormalize(): boolean {
    return this.rerankerType === 'normalize';
  }

  /**
   * Check if the current reranker type is MMR.
   */
  isMmr(): boolean {
    return this.rerankerType === 'mmr';
  }

  getTotalK(): number {
    if (this.searchType === 'hybrid') {
      return this.semanticK + this.keywordK;
    } else if (this.searchType === 'semantic') {
      return this.semanticK;
    } else {
      return this.keywordK;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // AGENT SERVICE
  // ═══════════════════════════════════════════════════════════════════════════════

  private loadAgents(): void {
    this.agentsLoading = true;
    this.cdr.markForCheck();
    this.agentService.getAllAgents().subscribe({
      next: (agents: AgentProvider[]) => {
        this.ngZone.run(() => {
          this.agents = agents;
          this.agentsLoading = false;

          // Auto-select default or first available agent
          const defaultAgent = agents.find((a: AgentProvider) => a.isDefault && a.available);
          const firstAvailable = agents.find((a: AgentProvider) => a.available);
          this.selectedAgent = defaultAgent || firstAvailable || null;
          if (this.selectedAgent) {
            this.loadAgentCapabilities(this.selectedAgent);
          }
          this.cdr.detectChanges();
        });
      },
      error: () => {
        this.ngZone.run(() => {
          this.agentsLoading = false;
          this.cdr.detectChanges();
        });
      }
    });
  }

  /**
   * Refresh agent availability by re-checking CLI installations.
   * Calls backend to run version checks on each agent command.
   */
  refreshAgents(): void {
    this.agentsLoading = true;
    this.cdr.markForCheck();
    this.agentService.refreshAllAgents().subscribe({
      next: (agents: AgentProvider[]) => {
        this.ngZone.run(() => {
          this.agents = agents;
          this.agentsLoading = false;

          // Re-select if current agent is no longer available
          if (this.selectedAgent && !this.selectedAgent.available) {
            const stillAvailable = agents.find((a: AgentProvider) => a.name === this.selectedAgent?.name && a.available);
            if (stillAvailable) {
              this.selectedAgent = stillAvailable;
            } else {
              // Select first available instead
              const firstAvailable = agents.find((a: AgentProvider) => a.available);
              this.selectedAgent = firstAvailable || null;
            }
          }
          this.cdr.detectChanges();
        });
      },
      error: () => {
        this.ngZone.run(() => {
          this.agentsLoading = false;
          this.cdr.detectChanges();
        });
      }
    });
  }

  selectAgent(agent: AgentProvider): void {
    this.selectedAgent = agent;
    this.skipPermissions = agent.skipPermissions;
    if (this.currentSession) {
      this.currentSession.agentName = agent.name;
      this.saveSessions();
    }
    this.loadAgentCapabilities(agent);
    this.cdr.detectChanges();
  }

  private loadAgentCapabilities(agent: AgentProvider): void {
    if (agent.agentType !== 'API') {
      // CLI agents (claude, codex) handle vision natively via passthrough
      this.agentSupportsVision = true;
      this.cdr.markForCheck();
      return;
    }
    this.http.get<any>(`${this.modelContextService.backendUrl}/agents/api-config/${encodeURIComponent(agent.name)}/capabilities`)
      .subscribe({
        next: (caps) => {
          this.agentSupportsVision = caps.supportsVision === true;
          this.cdr.markForCheck();
        },
        error: () => {
          this.agentSupportsVision = false;
          this.cdr.markForCheck();
        }
      });
  }

  onSkipPermissionsChange(): void {
    if (this.selectedAgent) {
      this.agentService.updateSkipPermissions(this.selectedAgent.name, this.skipPermissions)
        .subscribe({
          next: () => {
            if (this.selectedAgent) {
              this.selectedAgent.skipPermissions = this.skipPermissions;
            }
          },
          error: (err: any) => console.error('Failed to persist skipPermissions:', err)
        });
    }
  }

  getAgentIcon(agentName: string): string {
    const icons: { [key: string]: string } = {
      'claude': '🤖',
      'claude-cli': '🤖',
      'codex': '💻',
      'codex-cli': '💻',
      'gemini': '✨',
      'gemini-cli': '✨'
    };
    // For API agents, use a different icon
    const agent = this.agents.find(a => a.name === agentName);
    if (agent?.agentType === 'API') {
      return '🌐';
    }
    return icons[agentName] || '🔧';
  }

  getAvailableAgentCount(): number {
    return this.agents.filter(a => a.available).length;
  }

  isApiAgent(agent: AgentProvider | null): boolean {
    return agent?.agentType === 'API';
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // API AGENT CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════════

  toggleApiAgentConfig(): void {
    this.showApiAgentConfig = !this.showApiAgentConfig;
    if (!this.showApiAgentConfig) {
      this.resetApiAgentForm();
    }
    this.cdr.detectChanges();
  }

  resetApiAgentForm(): void {
    this.apiAgentName = '';
    this.apiAgentDisplayName = '';
    this.apiAgentEndpointUrl = '';
    this.apiAgentApiKey = '';
    this.apiAgentModelName = '';
    this.apiAgentTemperature = 0.7;
    this.apiAgentMaxTokens = 4096;
    this.apiAgentTestResult = '';
    this.editingApiAgentName = null;
  }

  editApiAgent(agent: AgentProvider): void {
    this.showApiAgentConfig = true;
    this.editingApiAgentName = agent.name;
    this.apiAgentName = agent.name;
    this.apiAgentDisplayName = agent.displayName;
    this.apiAgentEndpointUrl = agent.endpointUrl || '';
    this.apiAgentApiKey = ''; // Don't show masked key
    this.apiAgentModelName = agent.modelName || '';
    this.apiAgentTemperature = agent.temperature ?? 0.7;
    this.apiAgentMaxTokens = agent.maxTokens ?? 4096;
    this.apiAgentTestResult = '';
    this.cdr.markForCheck();
  }

  saveApiAgent(): void {
    if (!this.apiAgentName || !this.apiAgentEndpointUrl || !this.apiAgentModelName) {
      this.apiAgentTestResult = 'Name, endpoint URL, and model name are required.';
      this.cdr.markForCheck();
      return;
    }

    this.apiAgentSaving = true;
    const config: ApiAgentConfigRequest = {
      name: this.apiAgentName,
      displayName: this.apiAgentDisplayName || this.apiAgentName,
      endpointUrl: this.apiAgentEndpointUrl,
      apiKey: this.apiAgentApiKey || undefined,
      modelName: this.apiAgentModelName,
      temperature: this.apiAgentTemperature,
      maxTokens: this.apiAgentMaxTokens
    };

    const obs = this.editingApiAgentName
      ? this.agentService.updateApiAgentConfig(this.editingApiAgentName, config)
      : this.agentService.addApiAgentConfig(config);

    obs.subscribe({
      next: (result: any) => {
        this.apiAgentSaving = false;
        this.apiAgentTestResult = result.message || 'Saved successfully';
        this.resetApiAgentForm();
        this.showApiAgentConfig = false;
        this.refreshAgents();
        this.cdr.markForCheck();
      },
      error: (err: any) => {
        this.apiAgentSaving = false;
        this.apiAgentTestResult = 'Error: ' + (err.error?.error || err.message || 'Failed to save');
        this.cdr.markForCheck();
      }
    });
  }

  deleteApiAgent(agent: AgentProvider): void {
    if (!confirm(`Remove API agent "${agent.displayName}"?`)) return;

    this.agentService.deleteApiAgentConfig(agent.name).subscribe({
      next: () => {
        if (this.selectedAgent?.name === agent.name) {
          this.selectedAgent = null;
        }
        this.refreshAgents();
      },
      error: (err: any) => {
        console.error('Failed to delete API agent:', err);
      }
    });
  }

  testApiEndpoint(): void {
    if (!this.apiAgentEndpointUrl) {
      this.apiAgentTestResult = 'Enter an endpoint URL first.';
      this.cdr.markForCheck();
      return;
    }

    this.apiAgentTestLoading = true;
    this.apiAgentTestResult = 'Testing...';
    this.cdr.markForCheck();

    this.agentService.testApiEndpoint({
      endpointUrl: this.apiAgentEndpointUrl,
      apiKey: this.apiAgentApiKey || undefined
    }).subscribe({
      next: (result: any) => {
        this.apiAgentTestLoading = false;
        if (result.reachable) {
          const models = result.models ? result.models.slice(0, 5).join(', ') : '';
          this.apiAgentTestResult = 'Connected! ' + (models ? 'Models: ' + models : '');
        } else {
          this.apiAgentTestResult = 'Failed: ' + (result.error || 'Endpoint not reachable');
        }
        this.cdr.markForCheck();
      },
      error: (err: any) => {
        this.apiAgentTestLoading = false;
        this.apiAgentTestResult = 'Error: ' + (err.message || 'Connection failed');
        this.cdr.markForCheck();
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // KOMPILE LOCAL MODEL
  // ═══════════════════════════════════════════════════════════════════════════════

  loadKompileLocalStatus(): void {
    this.agentService.getKompileLocalStatus().subscribe({
      next: (status: KompileLocalModelStatus) => {
        this.kompileLocalStatus = status;
        if (status.stagingUrl) {
          this.kompileLocalStagingUrl = status.stagingUrl;
        }
        this.cdr.markForCheck();
      },
      error: () => {
        this.kompileLocalStatus = null;
        this.cdr.markForCheck();
      }
    });
  }

  connectKompileLocal(): void {
    this.kompileLocalLoading = true;
    this.cdr.markForCheck();

    this.agentService.connectKompileLocal(this.kompileLocalStagingUrl).subscribe({
      next: () => {
        this.kompileLocalLoading = false;
        this.loadKompileLocalStatus();
        this.refreshAgents();
      },
      error: (err: any) => {
        this.kompileLocalLoading = false;
        console.error('Failed to connect kompile-local:', err);
        this.cdr.markForCheck();
      }
    });
  }

  disconnectKompileLocal(): void {
    this.kompileLocalLoading = true;
    this.cdr.markForCheck();

    this.agentService.disconnectKompileLocal().subscribe({
      next: () => {
        this.kompileLocalLoading = false;
        this.loadKompileLocalStatus();
        this.refreshAgents();
      },
      error: (err: any) => {
        this.kompileLocalLoading = false;
        console.error('Failed to disconnect kompile-local:', err);
        this.cdr.markForCheck();
      }
    });
  }

  refreshKompileLocalStatus(): void {
    this.kompileLocalLoading = true;
    this.cdr.markForCheck();

    this.agentService.discoverKompileLocal().subscribe({
      next: () => {
        this.kompileLocalLoading = false;
        this.loadKompileLocalStatus();
        this.refreshAgents();
      },
      error: (err: any) => {
        this.kompileLocalLoading = false;
        console.error('Failed to refresh kompile-local:', err);
        this.cdr.markForCheck();
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FOLDER SERVICE
  // ═══════════════════════════════════════════════════════════════════════════════

  private loadFolders(): void {
    this.foldersLoading = true;
    this.cdr.markForCheck();

    this.folderService.getFolders().subscribe({
      next: (folders) => {
        this.folders = folders;
        this.foldersLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.foldersLoading = false;
        this.cdr.markForCheck();
      }
    });
  }

  toggleFolderSidebar(): void {
    this.showFolderSidebar = !this.showFolderSidebar;
    this.cdr.detectChanges();
  }

  onFolderSelected(folder: ChatFolder | null): void {
    this.selectedFolder = folder;
    this.folderService.selectFolder(folder);
    this.cdr.markForCheck();
  }

  clearFolderSelection(): void {
    this.selectedFolder = null;
    this.folderService.clearSelection();
    this.cdr.markForCheck();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CLI TRANSCRIPT INTEGRATION
  // ═══════════════════════════════════════════════════════════════════════════════

  switchSidebarTab(tab: 'app' | 'cli'): void {
    this.sidebarTab = tab;
    if (tab === 'cli' && this.cliSessions.length === 0) {
      this.loadCliSessions();
      this.loadCliSources();
    }
    this.cdr.markForCheck();
  }

  loadCliSources(): void {
    this.cliTranscriptService.discoverSources().subscribe({
      next: (sources) => {
        this.cliSources = sources;
        this.cdr.markForCheck();
      },
      error: () => {
        this.cliSources = {};
        this.cdr.markForCheck();
      }
    });
  }

  loadCliSessions(): void {
    this.cliLoading = true;
    this.cdr.markForCheck();

    this.cliTranscriptService.listSessions(this.cliSourceFilter).subscribe({
      next: (sessions) => {
        this.cliSessions = sessions;
        this.cliLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.cliSessions = [];
        this.cliLoading = false;
        this.cdr.markForCheck();
      }
    });
  }

  onCliSourceFilterChange(source: string): void {
    this.cliSourceFilter = source;
    this.loadCliSessions();
  }

  previewCliSession(session: CliSessionSummary): void {
    this.cliPreviewLoading = true;
    this.cliPreview = null;
    this.cdr.markForCheck();

    this.cliTranscriptService.getTranscript(session.sessionId, session.source).subscribe({
      next: (detail) => {
        this.cliPreview = detail;
        this.cliPreviewLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.cliPreviewLoading = false;
        this.cdr.markForCheck();
      }
    });
  }

  importCliSession(session: CliSessionSummary): void {
    this.cliImporting = session.sessionId;
    this.cdr.markForCheck();

    this.cliTranscriptService.importTranscript(session.sessionId, session.source).subscribe({
      next: (imported) => {
        this.cliImporting = null;
        // Load the imported session into the main chat
        this.loadCliSessions(); // Refresh CLI list
        // Switch to app tab to see the imported session
        this.sidebarTab = 'app';
        this.loadSessions();
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.cliImporting = null;
        const errorMsg = err?.error?.error || 'Import failed';
        console.error('CLI import failed:', errorMsg);
        this.cdr.markForCheck();
      }
    });
  }

  loadCliTranscriptIntoChat(detail: CliTranscriptDetail): void {
    // Create a new session from the CLI transcript
    this.newChat();
    if (this.currentSession) {
      this.currentSession.name = detail.title || 'Imported: ' + detail.source;
    }

    // Load turns as messages
    for (const turn of detail.turns) {
      const msg: UnifiedMessage = {
        id: 'cli-' + Math.random().toString(36).substring(2),
        role: turn.role === 'user' ? 'user' : 'assistant',
        content: turn.content,
        timestamp: new Date()
      };
      this.messages.push(msg);
    }

    this.sidebarTab = 'app';
    this.shouldScrollToBottom = true;
    this.cdr.markForCheck();
  }

  exportCurrentSession(): void {
    if (!this.currentSession) return;

    // Try to find a backend session ID. If the current session is stored in the backend, use that.
    // Otherwise, we need to save it first.
    const sessionId = this.currentSession.id;

    // Save current messages to backend first, then export
    this.chatHistoryService.createSession(this.currentSession.name || 'Exported Chat').subscribe({
      next: (created) => {
        // Add all messages
        const addMessages = this.messages.map(msg =>
          this.chatHistoryService.addMessage(created.sessionId, {
            role: msg.role === 'user' ? 'USER' : msg.role === 'assistant' ? 'ASSISTANT' : 'SYSTEM',
            content: msg.content
          }).toPromise()
        );

        Promise.all(addMessages).then(() => {
          // Now export
          this.cliTranscriptService.exportSession(created.sessionId).subscribe({
            next: (result) => {
              console.log('Exported to:', result.transcriptPath);
              this.cdr.markForCheck();
            },
            error: (err) => {
              console.error('Export failed:', err);
            }
          });
        });
      },
      error: (err) => {
        console.error('Failed to save session for export:', err);
      }
    });
  }

  getEffectiveSource(session: ChatSession): string | undefined {
    return session.source || this.agentNameToSource(session.agentName);
  }

  getSourceDisplayName(source: string): string {
    switch (source) {
      case 'kompile': return 'Kompile';
      case 'claude-code': return 'Claude';
      case 'opencode': return 'OpenCode';
      case 'codex': return 'Codex';
      case 'qwen': return 'Qwen';
      case 'gemini': return 'Gemini';
      case 'pi': return 'Pi';
      default: return source;
    }
  }

  getSourceColor(source: string): string {
    switch (source) {
      case 'kompile': return '#4CAF50';
      case 'claude-code': return '#D97706';
      case 'opencode': return '#2196F3';
      case 'codex': return '#9C27B0';
      case 'qwen': return '#F44336';
      case 'gemini': return '#1A73E8';
      case 'pi': return '#FF6F00';
      default: return '#757575';
    }
  }

  formatCliDate(timestamp: number): string {
    if (!timestamp) return '';
    const date = new Date(timestamp);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));

    if (diffDays === 0) return 'Today';
    if (diffDays === 1) return 'Yesterday';
    if (diffDays < 7) return diffDays + 'd ago';
    return date.toLocaleDateString();
  }

  closeCliPreview(): void {
    this.cliPreview = null;
    this.cdr.markForCheck();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ATTACHMENT HANDLING
  // ═══════════════════════════════════════════════════════════════════════════════

  @ViewChild('fileInput') private fileInput!: ElementRef<HTMLInputElement>;

  onAttachClick(): void {
    this.fileInput?.nativeElement?.click();
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files) {
      this.processFiles(Array.from(input.files));
      input.value = ''; // reset so same file can be re-selected
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = true;
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;
    if (event.dataTransfer?.files) {
      this.processFiles(Array.from(event.dataTransfer.files));
    }
  }

  onPaste(event: ClipboardEvent): void {
    const items = event.clipboardData?.items;
    if (!items) return;
    const files: File[] = [];
    for (let i = 0; i < items.length; i++) {
      if (items[i].kind === 'file') {
        const file = items[i].getAsFile();
        if (file) files.push(file);
      }
    }
    if (files.length > 0) {
      this.processFiles(files);
    }
  }

  private processFiles(files: File[]): void {
    for (const file of files) {
      if (file.size > 5 * 1024 * 1024) {
        console.warn(`File ${file.name} exceeds 5MB limit, skipping`);
        continue;
      }
      const isImage = file.type.startsWith('image/');
      if (isImage && !this.agentSupportsVision) {
        console.warn(`Agent does not support vision, skipping image ${file.name}`);
        continue;
      }

      const reader = new FileReader();
      reader.onload = () => {
        const attachment: MessageAttachment = {
          filename: file.name,
          mimeType: file.type || 'application/octet-stream',
          isImage: isImage
        };
        if (isImage) {
          const dataUrl = reader.result as string;
          attachment.base64Data = dataUrl.split(',')[1];
          attachment.previewUrl = dataUrl;
        } else {
          attachment.textContent = reader.result as string;
        }
        this.pendingAttachments.push(attachment);
        this.cdr.markForCheck();
      };
      if (isImage) {
        reader.readAsDataURL(file);
      } else {
        reader.readAsText(file);
      }
    }
  }

  removeAttachment(index: number): void {
    this.pendingAttachments.splice(index, 1);
    this.cdr.markForCheck();
  }

  hasImageAttachments(): boolean {
    return this.pendingAttachments.some(a => a.isImage);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // UI HELPERS
  // ═══════════════════════════════════════════════════════════════════════════════

  toggleSettings(): void {
    this.showSettings = !this.showSettings;
    this.cdr.detectChanges();
  }

  toggleHistorySidebar(): void {
    this.showHistorySidebar = !this.showHistorySidebar;
    this.cdr.detectChanges();
  }

  toggleShowArchived(): void {
    this.showArchivedChats = !this.showArchivedChats;
    this.cdr.detectChanges();
  }

  toggleDocuments(message: UnifiedMessage): void {
    message.documentsExpanded = !message.documentsExpanded;
  }

  toggleQueryInfo(message: UnifiedMessage): void {
    if (message.queryInfo) {
      message.queryInfo.expanded = !message.queryInfo.expanded;
    }
  }

  toggleSources(message: UnifiedMessage): void {
    message._sourcesExpanded = !message._sourcesExpanded;
    this.cdr.detectChanges();
  }

  /**
   * Navigate to a source document (opens in document manager or new tab)
   * @param source The source object with document information
   */
  navigateToSource(source: any): void {
    if (source?.documentId) {
      // Emit event to parent for document navigation or open in new tab
      const url = `/api/documents/${source.documentId}`;
      window.open(url, '_blank');
    } else if (source?.sourceUrl) {
      window.open(source.sourceUrl, '_blank');
    } else if (source?.sourceName) {
      // Search for document by name as fallback
      console.log('Navigate to source:', source.sourceName);
    }
    this.cdr.markForCheck();
  }

  /**
   * Scroll to a specific source in the expanded sources section
   * @param message The message containing sources
   * @param sourceIndex The index of the source to scroll to
   */
  scrollToSource(message: UnifiedMessage, sourceIndex: number): void {
    // First expand sources if collapsed
    if (!message._sourcesExpanded) {
      message._sourcesExpanded = true;
      this.cdr.markForCheck();
    }

    // Highlight the source briefly
    if (message.sources && message.sources[sourceIndex]) {
      message.sources[sourceIndex]._highlighted = true;
      this.cdr.markForCheck();

      // Remove highlight after animation
      setTimeout(() => {
        if (message.sources && message.sources[sourceIndex]) {
          message.sources[sourceIndex]._highlighted = false;
          this.cdr.markForCheck();
        }
      }, 2000);
    }
  }

  /**
   * Check if message content contains source references like [1], [2]
   * @param content The message content
   * @returns True if content has source references
   */
  hasSourceReferences(content: string): boolean {
    return /\[\d+\]/.test(content);
  }

  /**
   * Get source reference indices from message content
   * @param content The message content
   * @returns Array of source indices found in content
   */
  getSourceIndices(content: string): number[] {
    const matches = content.match(/\[(\d+)\]/g) || [];
    return [...new Set(matches.map(m => parseInt(m.replace(/[\[\]]/g, ''), 10)))];
  }

  /**
   * Copy source content to clipboard
   * @param source The source to copy
   * @param event Click event to stop propagation
   */
  copySourceContent(source: any, event: Event): void {
    event.stopPropagation();
    const content = source.content || source.preview || '';
    const sourceName = source.sourceName || 'Source';
    const fullText = `Source: ${sourceName}\n\n${content}`;

    navigator.clipboard.writeText(fullText).then(() => {
      source._copied = true;
      this.cdr.markForCheck();
      setTimeout(() => {
        source._copied = false;
        this.cdr.markForCheck();
      }, 2000);
    }).catch(err => {
      console.error('Failed to copy source:', err);
    });
  }

  /**
   * Open source in knowledge graph view
   * @param source The source to view in graph
   */
  viewSourceInGraph(source: any): void {
    if (source?.documentId || source?.nodeId) {
      const nodeId = source.nodeId || source.documentId;
      // Navigate to graph view with this node highlighted
      // This would typically emit an event to parent component
      console.log('View in graph:', nodeId);
      // Could trigger navigation: this.router.navigate(['/graph'], { queryParams: { nodeId } });
    }
  }

  /**
   * Transform message content to include clickable source reference links
   * Converts [1], [2], etc. into clickable spans that highlight the corresponding source
   * @param message The message containing content and sources
   * @returns SafeHtml with clickable source references
   */
  getContentWithSourceLinks(message: UnifiedMessage): SafeHtml {
    if (!message.sources || message.sources.length === 0 || !message.content) {
      return this.getRenderedMarkdown(message);
    }

    // Render markdown first, then inject source reference links
    let content = this.markdownRenderer.renderMarkdown(message.content);

    // Replace [n] patterns with clickable links (1-indexed)
    content = content.replace(/\[(\d+)\]/g, (match, num) => {
      const index = parseInt(num, 10) - 1; // Convert to 0-indexed
      if (index >= 0 && index < message.sources!.length) {
        const source = message.sources![index];
        const tooltip = source.sourceName || `Source ${index + 1}`;
        return `<span class="source-ref-link" data-message-id="${message.id}" data-source-index="${index}" title="${this.escapeHtml(tooltip)}">[${num}]</span>`;
      }
      return match; // Return unchanged if index is out of bounds
    });

    return this.sanitizer.bypassSecurityTrustHtml(content);
  }

  /**
   * Escape HTML special characters to prevent XSS
   * @param text The text to escape
   * @returns Escaped text safe for innerHTML
   */
  private escapeHtml(text: string): string {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  /**
   * Check if message has source references that can be linked
   * @param message The message to check
   * @returns True if message has both content with [n] references and sources
   */
  hasLinkableSourceRefs(message: UnifiedMessage): boolean {
    if (!message.content || !message.sources || message.sources.length === 0) {
      return false;
    }
    return /\[\d+\]/.test(message.content);
  }

  /**
   * Check if message content contains tool use blocks or thinking tags from CLI transcripts.
   */
  hasToolBlocks(message: UnifiedMessage): boolean {
    if (!message.content) return false;
    return /\[tool:[^\]]+\]/.test(message.content) || /<thinking>/.test(message.content);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // MARKDOWN RENDERING
  // ═══════════════════════════════════════════════════════════════════════════════

  private renderedMarkdownCache = new Map<string, SafeHtml>();
  private segmentCache = new Map<string, MessageSegment[]>();

  getRenderedMarkdown(message: UnifiedMessage): SafeHtml {
    if (!message.content) {
      return this.sanitizer.bypassSecurityTrustHtml('');
    }

    // Don't cache streaming messages — content changes every chunk
    if (message.isStreaming) {
      return this.sanitizer.bypassSecurityTrustHtml(
        this.markdownRenderer.renderMarkdown(message.content)
      );
    }

    const cacheKey = message.id + ':' + message.content.length;
    let cached = this.renderedMarkdownCache.get(cacheKey);
    if (!cached) {
      cached = this.sanitizer.bypassSecurityTrustHtml(
        this.markdownRenderer.renderMarkdown(message.content)
      );
      this.renderedMarkdownCache.set(cacheKey, cached);
      // Keep cache bounded
      if (this.renderedMarkdownCache.size > 500) {
        const firstKey = this.renderedMarkdownCache.keys().next().value;
        if (firstKey !== undefined) {
          this.renderedMarkdownCache.delete(firstKey);
        }
      }
    }
    return cached;
  }

  getMessageSegments(message: UnifiedMessage): MessageSegment[] {
    if (!message.content) return [];

    if (message.isStreaming) {
      // Streaming content changes every chunk, so it can't be cached — but we
      // still pre-render each segment here so the template binds to a value
      // instead of invoking the renderer on every change-detection pass.
      const live = this.markdownRenderer.parseMessageSegments(message.content, true);
      this.renderSegments(live);
      return live;
    }

    const cacheKey = message.id + ':' + message.content.length;
    let cached = this.segmentCache.get(cacheKey);
    if (!cached) {
      cached = this.markdownRenderer.parseMessageSegments(message.content, false);
      this.renderSegments(cached);
      this.segmentCache.set(cacheKey, cached);
      if (this.segmentCache.size > 500) {
        const firstKey = this.segmentCache.keys().next().value;
        if (firstKey !== undefined) {
          this.segmentCache.delete(firstKey);
        }
      }
    }
    return cached;
  }

  /**
   * Pre-render text/thinking segments to sanitized HTML once and store the
   * result on the segment. The chat re-renders frequently (a 2s sync poll plus
   * streaming updates); running marked + highlight.js for every segment on
   * every change-detection pass was the main cause of multi-second chat loads.
   */
  private renderSegments(segments: MessageSegment[]): void {
    for (const segment of segments) {
      if (segment.renderedContent === undefined &&
          (segment.type === 'text' || segment.type === 'thinking')) {
        segment.renderedContent = this.sanitizer.bypassSecurityTrustHtml(
          this.markdownRenderer.renderMarkdown(segment.content)
        );
      }
    }
  }

  /**
   * trackBy for the message list. Without a stable identity Angular tears down
   * and rebuilds every message row (and its rendered markdown) whenever the
   * `messages` array is reassigned, e.g. on session load.
   */
  trackByMessageId(_index: number, message: UnifiedMessage): string {
    return message.id;
  }

  handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    } else if (event.key === 'Escape' && this.isStreaming) {
      event.preventDefault();
      this.cancelStreaming();
    }
  }

  onUserInputChange(value: string): void {
    this.userInput = value;
    this.cdr.markForCheck();
  }

  private scrollToBottom(): void {
    try {
      if (this.conversationArea?.nativeElement) {
        const element = this.conversationArea.nativeElement;
        element.scrollTop = element.scrollHeight;
      }
    } catch (err) {
      // Ignore scroll errors
    }
  }

  private generateId(): string {
    return Date.now().toString(36) + Math.random().toString(36).substring(2);
  }

  formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  }

  getSessionTokenUsage(): { totalInput: number; totalOutput: number; totalTokens: number } {
    let totalInput = 0;
    let totalOutput = 0;
    for (const msg of this.messages) {
      if (msg.tokenMetrics) {
        totalInput += msg.tokenMetrics.inputTokens || 0;
        totalOutput += msg.tokenMetrics.outputTokens || 0;
      }
    }
    return { totalInput, totalOutput, totalTokens: totalInput + totalOutput };
  }

  formatTokenCount(count: number): string {
    if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M`;
    if (count >= 1_000) return `${(count / 1_000).toFixed(1)}k`;
    return count.toString();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // MESSAGE ACTIONS (Backend-Driven Pattern)
  // ═══════════════════════════════════════════════════════════════════════════════
  // All actions use message IDs to fetch full content from backend when available.
  // This ensures actions operate on complete content even if display is truncated.

  /**
   * Copy message content to clipboard.
   * If dbId is available, fetches full content from backend first.
   *
   * @param content Rendered content (fallback if no dbId or fetch fails)
   * @param index Message index for UI feedback
   * @param dbId Optional database message ID for fetching full content
   */
  copyMessage(content: string, index: number, dbId?: number): void {
    if (dbId && dbId > 0) {
      this.chatHistoryService.getMessageContent(dbId).subscribe({
        next: (fullContent) => this.copyToClipboard(fullContent, index),
        error: () => this.copyToClipboard(content, index) // Fallback
      });
    } else {
      this.copyToClipboard(content, index);
    }
  }

  private copyToClipboard(content: string, index: number): void {
    navigator.clipboard.writeText(content).then(() => {
      this.copiedIndex = index;
      setTimeout(() => this.copiedIndex = null, 2000);
    }).catch(err => {
      console.error('Failed to copy:', err);
    });
  }

  /**
   * Fork conversation from a specific message.
   * Creates a new session with all messages up to and including the fork point.
   *
   * @param messageIndex Index of message to fork from
   */
  forkFromMessage(messageIndex: number): void {
    const message = this.messages[messageIndex];

    // Validate message has been saved
    if (!message.dbId || message.dbId === 0) {
      console.warn('Cannot fork: message not yet saved to database');
      // Fallback: use local messages
      this.createForkFromLocalMessages(messageIndex);
      return;
    }

    if (!this.currentSession) {
      console.warn('Cannot fork: no active session');
      return;
    }

    // Fetch full messages from backend up to fork point
    this.chatHistoryService.getMessagesUntil(this.currentSession.id, message.dbId).subscribe({
      next: (fullMessages) => {
        this.createForkFromBackendMessages(fullMessages);
      },
      error: (err) => {
        console.error('Failed to fetch messages for fork:', err);
        // Fallback to local messages
        this.createForkFromLocalMessages(messageIndex);
      }
    });
  }

  private createForkFromBackendMessages(backendMessages: ChatMessageDto[]): void {
    // Create new session with messages from backend
    const forkSession: ChatSession = {
      id: this.generateId(),
      name: `Fork: ${this.currentSession?.name || 'Chat'}`,
      messages: backendMessages.map(msg => ({
        id: this.generateId(),
        dbId: msg.id,
        role: msg.role.toLowerCase() as 'user' | 'assistant' | 'system',
        content: msg.content,
        timestamp: msg.createdAt ? new Date(msg.createdAt) : new Date()
      })),
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      agentName: this.currentSession?.agentName
    };

    this.sessions.unshift(forkSession);
    this.currentSession = forkSession;
    this.messages = [...forkSession.messages];
    this.agentSession = null; // Reset agent session for forked chat
    this.saveSessions();
    this.shouldScrollToBottom = true;
    this.updateMonitorSubscription();
  }

  private createForkFromLocalMessages(messageIndex: number): void {
    // Fork using local messages up to the specified index
    const forkedMessages = this.messages.slice(0, messageIndex + 1).map(msg => ({
      ...msg,
      id: this.generateId() // Generate new client IDs
    }));

    const forkSession: ChatSession = {
      id: this.generateId(),
      name: `Fork: ${this.currentSession?.name || 'Chat'}`,
      messages: forkedMessages,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      agentName: this.currentSession?.agentName
    };

    this.sessions.unshift(forkSession);
    this.currentSession = forkSession;
    this.messages = [...forkedMessages];
    this.agentSession = null;
    this.saveSessions();
    this.shouldScrollToBottom = true;
    this.updateMonitorSubscription();
  }

  /**
   * Regenerate a message (send the preceding user message again).
   * Creates a new assistant response.
   *
   * @param messageIndex Index of assistant message to regenerate
   */
  regenerateMessage(messageIndex: number): void {
    const message = this.messages[messageIndex];

    if (message.role !== 'assistant') {
      console.warn('Can only regenerate assistant messages');
      return;
    }

    // Find the preceding user message
    let userMessageIndex = messageIndex - 1;
    while (userMessageIndex >= 0 && this.messages[userMessageIndex].role !== 'user') {
      userMessageIndex--;
    }

    if (userMessageIndex < 0) {
      console.warn('No user message found to regenerate from');
      return;
    }

    const userMessage = this.messages[userMessageIndex];

    // Remove messages from the regeneration point onwards
    this.messages = this.messages.slice(0, messageIndex);
    this.updateCurrentSession();

    // Resend the user message to get a new response
    this.sendAgentMessage(userMessage.content);
  }

  /**
   * Edit and resend a user message.
   * Removes subsequent messages and creates a new conversation branch.
   *
   * @param messageIndex Index of user message to edit
   * @param newContent New content for the message
   */
  editMessage(messageIndex: number, newContent: string): void {
    const message = this.messages[messageIndex];

    if (message.role !== 'user') {
      console.warn('Can only edit user messages');
      return;
    }

    if (!newContent.trim()) {
      console.warn('Cannot set empty content');
      return;
    }

    // Remove messages from this point onwards
    this.messages = this.messages.slice(0, messageIndex);

    // Add edited message
    const editedMessage: UnifiedMessage = {
      id: this.generateId(),
      role: 'user',
      content: newContent.trim(),
      timestamp: new Date()
    };

    this.messages.push(editedMessage);
    this.updateCurrentSession();
    this.shouldScrollToBottom = true;

    // Send to get new response
    this.sendAgentMessage(newContent.trim());
  }

  /**
   * Export message content (download as text file).
   * If dbId is available, fetches full content from backend first.
   *
   * @param content Rendered content (fallback if no dbId or fetch fails)
   * @param dbId Optional database message ID for fetching full content
   */
  exportMessage(content: string, dbId?: number): void {
    if (dbId && dbId > 0) {
      this.chatHistoryService.getMessageContent(dbId).subscribe({
        next: (fullContent) => this.downloadAsTextFile(fullContent),
        error: () => this.downloadAsTextFile(content) // Fallback
      });
    } else {
      this.downloadAsTextFile(content);
    }
  }

  private downloadAsTextFile(content: string): void {
    const blob = new Blob([content], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `message-${new Date().toISOString().slice(0, 19).replace(/[:]/g, '-')}.txt`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ACTIVE MODEL CONTEXT
  // ═══════════════════════════════════════════════════════════════════════════════

  private initModelContext(): void {
    this.subscriptions.push(
      this.modelContextService.context$.subscribe(ctx => {
        this.activeModelContext = ctx;
        this.cdr.markForCheck();
      })
    );
    this.subscriptions.push(
      this.modelContextService.loading$.subscribe(loading => {
        this.modelContextLoading = loading;
        this.cdr.markForCheck();
      })
    );
    this.modelContextService.refresh();
  }

  refreshModelContext(): void {
    this.modelContextService.refresh();
  }

  switchEmbeddingModel(modelId: string): void {
    if (!modelId || this.embeddingSwitchLoading) return;
    if (modelId === this.activeModelContext?.embedding?.modelId) {
      this.showModelSwitchDropdown = false;
      return;
    }
    this.embeddingSwitchLoading = true;
    this.showModelSwitchDropdown = false;

    // Use the existing /api/models/embedding/switch/{modelId} endpoint
    const url = `${this.modelContextService.backendUrl}/models/embedding/switch/${encodeURIComponent(modelId)}`;
    this.http.post(url, {}).subscribe({
      next: () => {
        this.embeddingSwitchLoading = false;
        this.modelContextService.refresh();
        this.cdr.markForCheck();
      },
      error: (err: any) => {
        console.error('Failed to switch embedding model:', err);
        this.embeddingSwitchLoading = false;
        this.cdr.markForCheck();
      }
    });
  }

  openStagingModelCard(modelId: string): void {
    const url = this.modelContextService.getStagingModelCardUrl(modelId);
    if (url) {
      window.open(url, '_blank');
    }
  }

  openStagingApp(): void {
    const ctx = this.activeModelContext;
    if (ctx?.staging?.uiUrl) {
      window.open(ctx.staging.uiUrl, '_blank');
    }
  }

  toggleModelSwitchDropdown(): void {
    this.showModelSwitchDropdown = !this.showModelSwitchDropdown;
    this.cdr.detectChanges();
  }

  /**
   * Export entire conversation as a text file.
   */
  exportConversation(): void {
    if (this.messages.length === 0) {
      console.warn('No messages to export');
      return;
    }

    const content = this.messages.map(msg => {
      const role = msg.role.charAt(0).toUpperCase() + msg.role.slice(1);
      const timestamp = msg.timestamp.toLocaleString();
      return `[${timestamp}] ${role}:\n${msg.content}\n`;
    }).join('\n---\n\n');

    const filename = `${this.currentSession?.name || 'conversation'}-${new Date().toISOString().slice(0, 10)}.txt`;

    const blob = new Blob([content], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  /**
   * Check if a message has been saved to the database.
   */
  isMessageSaved(message: UnifiedMessage): boolean {
    return !!(message.dbId && message.dbId > 0);
  }
}
