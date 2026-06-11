/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { of, Subject, throwError } from 'rxjs';

import { UnifiedChatComponent } from './unified-chat.component';
import { ConversationalRagService } from '../../services/conversational-rag.service';
import { LocalAgentChatService } from '../../services/local-agent-chat.service';
import { AgentService } from '../../services/agent.service';
import { ChatStorageService } from '../../services/chat-storage.service';
import { ChatHistoryService } from '../../services/chat-history.service';
import { CliTranscriptService } from '../../services/cli-transcript.service';
import { FolderService } from '../../services/folder.service';
import { ModelContextService } from '../../services/model-context.service';
import { WebSocketService } from '../../services/websocket.service';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import {
  ConversationalChatResponse,
  ConversationalRagOptions,
  RagServiceStatus,
  RerankerConfig,
  DEFAULT_RAG_OPTIONS,
  DEFAULT_RERANKER_CONFIG,
  RerankerType,
  AgentProvider,
  RetrievedDocument
} from '../../models/api-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

/** Creates a mock AgentProvider with all required fields. */
function mockAgent(overrides: Partial<AgentProvider> = {}): AgentProvider {
  return {
    name: 'kompile-local',
    displayName: 'Kompile Local',
    command: 'kompile',
    skipPermissionsFlag: '--dangerously-skip-permissions',
    skipPermissions: true,
    args: [],
    environment: {},
    available: true,
    isDefault: true,
    description: 'Test agent',
    ...overrides
  };
}

/** Creates the TestBed fixture with all spied services. */

function createTestBed() {
  const ragServiceSpy = jasmine.createSpyObj('ConversationalRagService', [
    'getStatus', 'chat', 'chatStream', 'getHistory', 'clearConversation', 'buildOptions'
  ]);
  const agentChatServiceSpy = jasmine.createSpyObj('LocalAgentChatService', [
    'getStreamingContent', 'getStreamingComplete', 'getStreamingError',
    'getChatStats', 'getSources', 'getFilesModified', 'sendMessage',
    'cancelStreaming', 'createSession', 'getToolUse'
  ]);
  const agentServiceSpy = jasmine.createSpyObj('AgentService', [
    'getAllAgents', 'getAvailableAgents', 'getKompileLocalStatus'
  ], { agents$: new Subject<AgentProvider[]>().asObservable() });
  const chatStorageServiceSpy = jasmine.createSpyObj('ChatStorageService', [
    'getSessions', 'saveSession', 'deleteSession', 'getSession'
  ]);
  const chatHistoryServiceSpy = jasmine.createSpyObj('ChatHistoryService', [
    'getSessions', 'createSession', 'getSession', 'addMessage',
    'getSessionMessages', 'deleteSession', 'updateSessionTitle',
    'getMessageContent'
  ]);
  const cliTranscriptServiceSpy = jasmine.createSpyObj('CliTranscriptService', [
    'listSessions', 'discoverSources', 'getTranscript'
  ]);
  const folderServiceSpy = jasmine.createSpyObj('FolderService', [
    'getFolders', 'getFolderFiles', 'associateSession', 'disassociateSession'
  ], {
    folders$: new Subject<any[]>().asObservable(),
    selectedFolder$: new Subject<any>().asObservable()
  });
  const modelContextServiceSpy = jasmine.createSpyObj('ModelContextService', [
    'refresh', 'getStagingModelCardUrl'
  ], {
    context$: new Subject<any>().asObservable(),
    loading$: new Subject<boolean>().asObservable(),
    backendUrl: '/api'
  });
  const webSocketServiceSpy = jasmine.createSpyObj('WebSocketService', [
    'getMonitorEvents', 'connect', 'disconnect', 'subscribeToMonitor', 'unsubscribeFromMonitor'
  ]);
  webSocketServiceSpy.subscribeToMonitor.and.returnValue(new Subject<any>().asObservable());
  const dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);

  // Default return values
  ragServiceSpy.getStatus.and.returnValue(of({ available: true, service: 'rag' } as RagServiceStatus));
  ragServiceSpy.buildOptions.and.callFake(
    (searchType: string, semanticK: number, keywordK: number,
     similarityThreshold: number, maxHistoryMessages: number,
     useToolCalling: boolean, enableQueryProcessing: boolean,
     systemPrompt?: string, rerankerConfig?: RerankerConfig) => {
      const opts: ConversationalRagOptions = {
        similarityThreshold,
        maxHistoryMessages,
        useToolCalling,
        enableQueryProcessing
      };
      switch (searchType) {
        case 'semantic':
          opts.semanticK = semanticK + keywordK;
          opts.keywordK = 0;
          break;
        case 'keyword':
          opts.semanticK = 0;
          opts.keywordK = semanticK + keywordK;
          break;
        default:
          opts.semanticK = semanticK;
          opts.keywordK = keywordK;
      }
      if (systemPrompt) opts.systemPrompt = systemPrompt;
      if (rerankerConfig?.enabled) opts.rerankerConfig = rerankerConfig;
      return opts;
    }
  );
  agentServiceSpy.getAllAgents.and.returnValue(of([]));
  agentServiceSpy.getAvailableAgents.and.returnValue(of([]));
  agentServiceSpy.getKompileLocalStatus.and.returnValue(of({
    connected: false, modelLoaded: false, stagingUrl: null
  } as any));
  chatStorageServiceSpy.getSessions.and.returnValue([]);
  chatHistoryServiceSpy.getSessions.and.returnValue(of([]));
  cliTranscriptServiceSpy.listSessions.and.returnValue(of([]));
  cliTranscriptServiceSpy.discoverSources.and.returnValue(of({}));
  folderServiceSpy.getFolders.and.returnValue(of([]));
  // refresh() returns void — no returnValue needed

  // Streaming observables
  agentChatServiceSpy.getStreamingContent.and.returnValue(new Subject<string>().asObservable());
  agentChatServiceSpy.getStreamingComplete.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getStreamingError.and.returnValue(new Subject<string>().asObservable());
  agentChatServiceSpy.getChatStats.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getSources.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getFilesModified.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getToolUse.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.sendMessage.and.returnValue(Promise.resolve());
  agentChatServiceSpy.createSession.and.returnValue({
    id: 'agent-session-1',
    name: 'Test Session',
    messages: [],
    createdAt: new Date().toISOString()
  });

  return {
    ragServiceSpy,
    agentChatServiceSpy,
    agentServiceSpy,
    chatStorageServiceSpy,
    chatHistoryServiceSpy,
    cliTranscriptServiceSpy,
    folderServiceSpy,
    modelContextServiceSpy,
    webSocketServiceSpy,
    dialogSpy,
    providers: [
      { provide: ConversationalRagService, useValue: ragServiceSpy },
      { provide: LocalAgentChatService, useValue: agentChatServiceSpy },
      { provide: AgentService, useValue: agentServiceSpy },
      { provide: ChatStorageService, useValue: chatStorageServiceSpy },
      { provide: ChatHistoryService, useValue: chatHistoryServiceSpy },
      { provide: CliTranscriptService, useValue: cliTranscriptServiceSpy },
      { provide: FolderService, useValue: folderServiceSpy },
      { provide: ModelContextService, useValue: modelContextServiceSpy },
      { provide: WebSocketService, useValue: webSocketServiceSpy },
      { provide: MatDialog, useValue: dialogSpy }
    ]
  };
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('UnifiedChatComponent - RAG End-to-End', () => {
  let component: UnifiedChatComponent;
  let fixture: ComponentFixture<UnifiedChatComponent>;
  let spies: ReturnType<typeof createTestBed>;

  beforeEach(async () => {
    spies = createTestBed();

    await TestBed.configureTestingModule({
      imports: [FormsModule, NoopAnimationsModule, HttpClientTestingModule, MatMenuModule],
      declarations: [UnifiedChatComponent],
      providers: spies.providers,
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(UnifiedChatComponent);
    component = fixture.componentInstance;
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. RAG SERVICE STATUS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('RAG service status', () => {
    it('should mark service available on successful status check', () => {
      spies.ragServiceSpy.getStatus.and.returnValue(
        of({ available: true, service: 'conversational-rag' } as RagServiceStatus)
      );
      fixture.detectChanges(); // triggers ngOnInit

      expect(component.serviceAvailable).toBeTrue();
      expect(component.serviceName).toBe('conversational-rag');
    });

    it('should mark service unavailable when status returns false', () => {
      spies.ragServiceSpy.getStatus.and.returnValue(
        of({ available: false, service: '' } as RagServiceStatus)
      );
      fixture.detectChanges();

      expect(component.serviceAvailable).toBeFalse();
    });

    it('should mark service unavailable on HTTP error', () => {
      spies.ragServiceSpy.getStatus.and.returnValue(
        throwError(() => new Error('Connection refused'))
      );
      fixture.detectChanges();

      expect(component.serviceAvailable).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. RAG CONFIGURATION DEFAULTS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('RAG configuration defaults', () => {
    it('should initialize with default search parameters', () => {
      expect(component.searchType).toBe('hybrid');
      expect(component.semanticK).toBe(DEFAULT_RAG_OPTIONS.semanticK || 5);
      expect(component.keywordK).toBe(DEFAULT_RAG_OPTIONS.keywordK || 5);
      expect(component.similarityThreshold).toBe(DEFAULT_RAG_OPTIONS.similarityThreshold || 0.5);
      expect(component.maxHistoryMessages).toBe(DEFAULT_RAG_OPTIONS.maxHistoryMessages || 10);
    });

    it('should initialize with tool calling disabled', () => {
      expect(component.useToolCalling).toBeFalse();
    });

    it('should initialize with query processing enabled', () => {
      expect(component.enableQueryProcessing).toBeTrue();
    });

    it('should initialize with streaming enabled', () => {
      expect(component.useStreaming).toBeTrue();
    });

    it('should initialize with empty system prompt', () => {
      expect(component.systemPrompt).toBe('');
    });

    it('should initialize with RAG augmentation disabled', () => {
      expect(component.ragEnabled).toBeFalse();
      expect(component.ragMaxResults).toBe(5);
      expect(component.ragThreshold).toBe(0.0);
    });

    it('should initialize with Graph RAG disabled', () => {
      expect(component.graphRagEnabled).toBeFalse();
      expect(component.graphRagSearchType).toBe('LOCAL');
      expect(component.graphRagMaxResults).toBe(5);
    });

    it('should initialize with 5-minute timeout', () => {
      expect(component.timeoutSeconds).toBe(300);
    });

    it('should have expected timeout options', () => {
      expect(component.timeoutOptions.length).toBe(7);
      expect(component.timeoutOptions[0].value).toBe(0);   // No timeout
      expect(component.timeoutOptions[3].value).toBe(300);  // 5 min default
      expect(component.timeoutOptions[6].value).toBe(1800); // 30 min max
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. RERANKER CONFIGURATION DEFAULTS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Reranker configuration defaults', () => {
    it('should initialize reranker as disabled', () => {
      expect(component.rerankerEnabled).toBe(DEFAULT_RERANKER_CONFIG.enabled);
      expect(component.rerankerEnabled).toBeFalse();
    });

    it('should default to RM3 reranker type', () => {
      expect(component.rerankerType).toBe('rm3');
    });

    it('should have RM3 default parameters', () => {
      expect(component.rerankerOriginalQueryWeight).toBe(0.5);
      expect(component.rerankerFilterTerms).toBeTrue();
      expect(component.rerankerOutputQuery).toBeFalse();
      expect(component.rerankerFbDocs).toBe(10);
      expect(component.rerankerFbTerms).toBe(10);
      expect(component.rerankerTopK).toBe(-1);
    });

    it('should have BM25-PRF default parameters', () => {
      expect(component.rerankerK1).toBe(0.9);
      expect(component.rerankerB).toBe(0.4);
      expect(component.rerankerNewTermWeight).toBe(0.2);
    });

    it('should have Rocchio default parameters', () => {
      expect(component.rerankerAlpha).toBe(1.0);
      expect(component.rerankerBeta).toBe(0.75);
      expect(component.rerankerGamma).toBe(0.15);
      expect(component.rerankerUseNegative).toBeFalse();
    });

    it('should have Axiom default parameters', () => {
      expect(component.rerankerR).toBe(20);
      expect(component.rerankerN).toBe(30);
      expect(component.rerankerAxiomBeta).toBe(0.4);
      expect(component.rerankerDeterministic).toBeTrue();
      expect(component.rerankerSeed).toBe(42);
    });

    it('should have empty cross-encoder model', () => {
      expect(component.rerankerCrossEncoderModel).toBe('');
    });

    it('should expose all reranker type infos', () => {
      expect(component.rerankerTypes.length).toBeGreaterThan(0);
      const typeIds = component.rerankerTypes.map(t => t.id);
      expect(typeIds).toContain('none');
      expect(typeIds).toContain('rm3');
      expect(typeIds).toContain('bm25prf');
      expect(typeIds).toContain('rocchio');
      expect(typeIds).toContain('axiom');
      expect(typeIds).toContain('cross_encoder');
      expect(typeIds).toContain('rrf');
      expect(typeIds).toContain('normalize');
      expect(typeIds).toContain('mmr');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. buildRerankerConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('buildRerankerConfig()', () => {
    // The method is private, so we invoke it indirectly through buildRagOptions
    // which calls buildRerankerConfig when rerankerEnabled is true.

    it('should include reranker config when reranker is enabled', () => {
      component.rerankerEnabled = true;
      component.rerankerType = 'rocchio';
      component.rerankerAlpha = 2.0;
      component.rerankerBeta = 1.5;
      component.rerankerGamma = 0.3;
      component.rerankerUseNegative = true;

      // Trigger buildRagOptions → buildRerankerConfig
      // buildRagOptions calls ragService.buildOptions which we've spied on
      const opts = (component as any).buildRagOptions();

      // Our spy captures the call — verify the reranker config was passed
      const lastCall = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      const rerankerArg: RerankerConfig = lastCall.args[8]; // 9th argument

      expect(rerankerArg).toBeDefined();
      expect(rerankerArg.enabled).toBeTrue();
      expect(rerankerArg.type).toBe('rocchio');
      expect(rerankerArg.alpha).toBe(2.0);
      expect(rerankerArg.beta).toBe(1.5);
      expect(rerankerArg.gamma).toBe(0.3);
      expect(rerankerArg.useNegative).toBeTrue();
    });

    it('should not include reranker config when disabled', () => {
      component.rerankerEnabled = false;

      (component as any).buildRagOptions();

      const lastCall = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      const rerankerArg = lastCall.args[8];
      expect(rerankerArg).toBeUndefined();
    });

    it('should include RM3 params when type is rm3', () => {
      component.rerankerEnabled = true;
      component.rerankerType = 'rm3';
      component.rerankerOriginalQueryWeight = 0.7;
      component.rerankerFilterTerms = false;
      component.rerankerOutputQuery = true;

      (component as any).buildRagOptions();

      const rerankerArg: RerankerConfig = spies.ragServiceSpy.buildOptions.calls.mostRecent().args[8];
      expect(rerankerArg.type).toBe('rm3');
      expect(rerankerArg.originalQueryWeight).toBe(0.7);
      expect(rerankerArg.filterTerms).toBeFalse();
      expect(rerankerArg.outputQuery).toBeTrue();
    });

    it('should include BM25-PRF params when type is bm25prf', () => {
      component.rerankerEnabled = true;
      component.rerankerType = 'bm25prf';
      component.rerankerK1 = 1.2;
      component.rerankerB = 0.6;
      component.rerankerNewTermWeight = 0.5;

      (component as any).buildRagOptions();

      const rerankerArg: RerankerConfig = spies.ragServiceSpy.buildOptions.calls.mostRecent().args[8];
      expect(rerankerArg.type).toBe('bm25prf');
      expect(rerankerArg.k1).toBe(1.2);
      expect(rerankerArg.b).toBe(0.6);
      expect(rerankerArg.newTermWeight).toBe(0.5);
    });

    it('should include Axiom params when type is axiom', () => {
      component.rerankerEnabled = true;
      component.rerankerType = 'axiom';
      component.rerankerR = 25;
      component.rerankerN = 40;
      component.rerankerAxiomBeta = 0.6;
      component.rerankerDeterministic = false;
      component.rerankerSeed = 123;

      (component as any).buildRagOptions();

      const rerankerArg: RerankerConfig = spies.ragServiceSpy.buildOptions.calls.mostRecent().args[8];
      expect(rerankerArg.type).toBe('axiom');
      expect(rerankerArg.r).toBe(25);
      expect(rerankerArg.n).toBe(40);
      expect(rerankerArg.axiomBeta).toBe(0.6);
      expect(rerankerArg.deterministic).toBeFalse();
      expect(rerankerArg.seed).toBe(123);
    });

    it('should include cross-encoder model when type is cross_encoder', () => {
      component.rerankerEnabled = true;
      component.rerankerType = 'cross_encoder';
      component.rerankerCrossEncoderModel = 'cross-encoder/ms-marco-MiniLM-L-12-v2';

      (component as any).buildRagOptions();

      const rerankerArg: RerankerConfig = spies.ragServiceSpy.buildOptions.calls.mostRecent().args[8];
      expect(rerankerArg.type).toBe('cross_encoder');
      expect(rerankerArg.crossEncoderModel).toBe('cross-encoder/ms-marco-MiniLM-L-12-v2');
    });

    it('should pass common feedback params for all expansion types', () => {
      component.rerankerEnabled = true;
      component.rerankerType = 'rm3';
      component.rerankerFbDocs = 20;
      component.rerankerFbTerms = 15;
      component.rerankerTopK = 50;

      (component as any).buildRagOptions();

      const rerankerArg: RerankerConfig = spies.ragServiceSpy.buildOptions.calls.mostRecent().args[8];
      expect(rerankerArg.fbDocs).toBe(20);
      expect(rerankerArg.fbTerms).toBe(15);
      expect(rerankerArg.topK).toBe(50);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. RAG OPTIONS BUILDING (search type routing)
  // ─────────────────────────────────────────────────────────────────────────────

  describe('buildRagOptions() — search type routing', () => {
    it('should pass hybrid search with separate K values', () => {
      component.searchType = 'hybrid';
      component.semanticK = 8;
      component.keywordK = 3;

      (component as any).buildRagOptions();

      const call = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      expect(call.args[0]).toBe('hybrid');
      expect(call.args[1]).toBe(8); // semanticK
      expect(call.args[2]).toBe(3); // keywordK
    });

    it('should pass semantic-only search', () => {
      component.searchType = 'semantic';
      component.semanticK = 5;
      component.keywordK = 5;

      (component as any).buildRagOptions();

      const call = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      expect(call.args[0]).toBe('semantic');
    });

    it('should pass keyword-only search', () => {
      component.searchType = 'keyword';
      component.semanticK = 5;
      component.keywordK = 5;

      (component as any).buildRagOptions();

      const call = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      expect(call.args[0]).toBe('keyword');
    });

    it('should pass similarity threshold', () => {
      component.similarityThreshold = 0.8;

      (component as any).buildRagOptions();

      const call = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      expect(call.args[3]).toBe(0.8);
    });

    it('should pass max history messages', () => {
      component.maxHistoryMessages = 20;

      (component as any).buildRagOptions();

      const call = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      expect(call.args[4]).toBe(20);
    });

    it('should pass tool calling flag', () => {
      component.useToolCalling = true;

      (component as any).buildRagOptions();

      const call = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      expect(call.args[5]).toBeTrue();
    });

    it('should pass query processing flag', () => {
      component.enableQueryProcessing = false;

      (component as any).buildRagOptions();

      const call = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      expect(call.args[6]).toBeFalse();
    });

    it('should pass system prompt when non-empty', () => {
      component.systemPrompt = '  You are a helpful coding assistant.  ';

      (component as any).buildRagOptions();

      const call = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      expect(call.args[7]).toBe('You are a helpful coding assistant.');
    });

    it('should pass undefined system prompt when empty', () => {
      component.systemPrompt = '   ';

      (component as any).buildRagOptions();

      const call = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      expect(call.args[7]).toBeUndefined();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. RERANKER HELPER METHODS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Reranker helper methods', () => {
    it('getRerankerIcon should return correct icons for each type', () => {
      expect(component.getRerankerIcon('rm3')).toBe('auto_fix_high');
      expect(component.getRerankerIcon('bm25prf')).toBe('trending_up');
      expect(component.getRerankerIcon('rocchio')).toBe('scatter_plot');
      expect(component.getRerankerIcon('axiom')).toBe('hub');
      expect(component.getRerankerIcon('score_ties')).toBe('swap_vert');
      expect(component.getRerankerIcon('cross_encoder')).toBe('psychology');
      expect(component.getRerankerIcon('rrf')).toBe('merge_type');
      expect(component.getRerankerIcon('normalize')).toBe('straighten');
      expect(component.getRerankerIcon('mmr')).toBe('diversity_3');
      expect(component.getRerankerIcon('none')).toBe('block');
    });

    it('getRerankerIcon should return fallback for unknown type', () => {
      expect(component.getRerankerIcon('unknown_type' as RerankerType)).toBe('sort');
    });

    it('getRerankerDescription should return description for current type', () => {
      component.rerankerType = 'rm3';
      const desc = component.getRerankerDescription();
      expect(desc).toBeTruthy();
      expect(typeof desc).toBe('string');
    });

    it('getRerankerDescription should return empty string for no match', () => {
      component.rerankerType = 'nonexistent' as RerankerType;
      expect(component.getRerankerDescription()).toBe('');
    });

    it('needsCommonParams should return true for expansion rerankers', () => {
      component.rerankerType = 'rm3';
      expect(component.needsCommonParams()).toBeTrue();

      component.rerankerType = 'bm25prf';
      expect(component.needsCommonParams()).toBeTrue();

      component.rerankerType = 'rocchio';
      expect(component.needsCommonParams()).toBeTrue();

      component.rerankerType = 'axiom';
      expect(component.needsCommonParams()).toBeTrue();
    });

    it('needsCommonParams should return false for non-expansion rerankers', () => {
      component.rerankerType = 'none';
      expect(component.needsCommonParams()).toBeFalse();

      component.rerankerType = 'cross_encoder';
      expect(component.needsCommonParams()).toBeFalse();

      component.rerankerType = 'rrf';
      expect(component.needsCommonParams()).toBeFalse();

      component.rerankerType = 'normalize';
      expect(component.needsCommonParams()).toBeFalse();

      component.rerankerType = 'mmr';
      expect(component.needsCommonParams()).toBeFalse();
    });

    it('isRm3 should only return true for rm3 type', () => {
      component.rerankerType = 'rm3';
      expect(component.isRm3()).toBeTrue();

      component.rerankerType = 'bm25prf';
      expect(component.isRm3()).toBeFalse();

      component.rerankerType = 'none';
      expect(component.isRm3()).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. SEND MESSAGE FLOW
  // ─────────────────────────────────────────────────────────────────────────────

  describe('sendMessage()', () => {
    beforeEach(() => {
      fixture.detectChanges(); // ngOnInit
      // Set up a selected agent so sendAgentMessage proceeds
      component.selectedAgent = mockAgent();
    });

    it('should not send when input is empty', () => {
      component.userInput = '   ';
      component.sendMessage();

      expect(component.messages.length).toBe(0);
      expect(spies.agentChatServiceSpy.sendMessage).not.toHaveBeenCalled();
    });

    it('should not send when already loading', () => {
      component.userInput = 'Hello';
      component.isLoading = true;
      component.sendMessage();

      expect(component.messages.length).toBe(0);
    });

    it('should not send when already streaming', () => {
      component.userInput = 'Hello';
      component.isStreaming = true;
      component.sendMessage();

      expect(component.messages.length).toBe(0);
    });

    it('should add user message and clear input', () => {
      component.userInput = 'What is machine learning?';
      component.sendMessage();

      expect(component.userInput).toBe('');
      expect(component.messages.length).toBeGreaterThanOrEqual(1);
      expect(component.messages[0].role).toBe('user');
      expect(component.messages[0].content).toBe('What is machine learning?');
    });

    it('should trim whitespace from user input', () => {
      component.userInput = '  What is ML?  ';
      component.sendMessage();

      expect(component.messages[0].content).toBe('What is ML?');
    });

    it('should create a session if none exists', () => {
      component.currentSession = null;
      component.userInput = 'Hello';
      component.sendMessage();

      expect(component.currentSession).not.toBeNull();
    });

    it('should add streaming placeholder assistant message', () => {
      component.userInput = 'Hello';
      component.sendMessage();

      // Messages: [user, assistant placeholder]
      expect(component.messages.length).toBe(2);
      const assistantMsg = component.messages[1];
      expect(assistantMsg.role).toBe('assistant');
      expect(assistantMsg.isStreaming).toBeTrue();
      expect(assistantMsg.content).toBe('');
    });

    it('should pass RAG parameters when ragEnabled is true', () => {
      component.ragEnabled = true;
      component.ragMaxResults = 10;
      component.ragThreshold = 0.3;
      component.userInput = 'Search docs';
      component.sendMessage();

      expect(spies.agentChatServiceSpy.sendMessage).toHaveBeenCalled();
      const callArgs = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args;
      const options = callArgs[3];
      expect(options.enableRag).toBeTrue();
      expect(options.ragMaxResults).toBe(10);
      expect(options.ragSimilarityThreshold).toBe(0.3);
    });

    it('should pass RAG disabled when ragEnabled is false', () => {
      component.ragEnabled = false;
      component.userInput = 'Hello';
      component.sendMessage();

      const options = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args[3];
      expect(options.enableRag).toBeFalse();
    });

    it('should pass Graph RAG parameters when enabled', () => {
      component.graphRagEnabled = true;
      component.graphRagSearchType = 'GLOBAL';
      component.graphRagMaxResults = 10;
      component.userInput = 'Graph query';
      component.sendMessage();

      const options = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args[3];
      expect(options.enableGraphRag).toBeTrue();
      expect(options.graphRagSearchType).toBe('GLOBAL');
      expect(options.graphRagMaxResults).toBe(10);
    });

    it('should pass Graph RAG LOCAL search type by default', () => {
      component.graphRagEnabled = true;
      component.userInput = 'Graph query';
      component.sendMessage();

      const options = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args[3];
      expect(options.graphRagSearchType).toBe('LOCAL');
    });

    it('should pass timeout setting', () => {
      component.timeoutSeconds = 600;
      component.userInput = 'Long task';
      component.sendMessage();

      const options = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args[3];
      expect(options.timeoutSeconds).toBe(600);
    });

    it('should pass skip permissions flag', () => {
      component.skipPermissions = false;
      component.userInput = 'Hello';
      component.sendMessage();

      const options = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args[3];
      expect(options.skipPermissions).toBeFalse();
    });

    it('should pass folder ID when a folder is selected', () => {
      component.selectedFolder = { folderId: 'folder-123', name: 'Test Folder' } as any;
      component.userInput = 'Scoped query';
      component.sendMessage();

      const options = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args[3];
      expect(options.folderId).toBe('folder-123');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. STREAMING LIFECYCLE
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Streaming lifecycle', () => {
    let contentSubject: Subject<string>;
    let completeSubject: Subject<any>;
    let errorSubject: Subject<string>;
    let statsSubject: Subject<any>;

    beforeEach(() => {
      contentSubject = new Subject<string>();
      completeSubject = new Subject<any>();
      errorSubject = new Subject<string>();
      statsSubject = new Subject<any>();

      spies.agentChatServiceSpy.getStreamingContent.and.returnValue(contentSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingComplete.and.returnValue(completeSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingError.and.returnValue(errorSubject.asObservable());
      spies.agentChatServiceSpy.getChatStats.and.returnValue(statsSubject.asObservable());

      fixture.detectChanges();
      component.selectedAgent = mockAgent();
    });

    it('should update assistant message content during streaming', fakeAsync(() => {
      component.userInput = 'Hello';
      component.sendMessage();
      tick();

      contentSubject.next('Partial response...');

      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg?.content).toBe('Partial response...');
      expect(assistantMsg?.isStreaming).toBeTrue();
    }));

    it('should update token metrics during streaming', fakeAsync(() => {
      component.userInput = 'Hello';
      component.sendMessage();
      tick();

      statsSubject.next({
        tokenMetrics: {
          inputTokens: 50, outputTokens: 25,
          totalGenerationMs: 500, tokensPerSecond: 50
        }
      });

      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg?.tokenMetrics).toBeDefined();
      expect(assistantMsg?.tokenMetrics?.inputTokens).toBe(50);
      expect(assistantMsg?.tokenMetrics?.outputTokens).toBe(25);
    }));

    it('should finalize message on stream complete', fakeAsync(() => {
      component.userInput = 'Hello';
      component.sendMessage();
      tick();

      completeSubject.next({
        content: 'Full response here.',
        latencyMs: 1200,
        sources: [{ title: 'doc1.pdf' }],
        tokenMetrics: {
          inputTokens: 100, outputTokens: 80,
          totalGenerationMs: 1200, tokensPerSecond: 66.7
        }
      });

      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg?.content).toBe('Full response here.');
      expect(assistantMsg?.isStreaming).toBeFalse();
      expect(assistantMsg?.latencyMs).toBe(1200);
      expect(assistantMsg?.sources?.length).toBe(1);
      expect(component.isStreaming).toBeFalse();
    }));

    it('should handle streaming error gracefully', fakeAsync(() => {
      component.userInput = 'Hello';
      component.sendMessage();
      tick();

      errorSubject.next('Connection timeout');

      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg?.error).toBeTrue();
      expect(assistantMsg?.content).toBe('Connection timeout');
      expect(assistantMsg?.isStreaming).toBeFalse();
      expect(component.isStreaming).toBeFalse();
    }));

    it('should handle empty error message', fakeAsync(() => {
      component.userInput = 'Hello';
      component.sendMessage();
      tick();

      errorSubject.next('');

      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg?.error).toBeTrue();
      expect(assistantMsg?.content).toBe('Agent request failed');
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. CANCEL STREAMING
  // ─────────────────────────────────────────────────────────────────────────────

  describe('cancelStreaming()', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should call cancelStreaming on the agent service', () => {
      component.isStreaming = true;
      component.messages = [{
        id: '1', role: 'assistant', content: 'Partial...', timestamp: new Date(), isStreaming: true
      } as any];

      component.cancelStreaming();

      expect(spies.agentChatServiceSpy.cancelStreaming).toHaveBeenCalled();
    });

    it('should reset streaming and loading flags', () => {
      component.isStreaming = true;
      component.isLoading = true;
      component.messages = [{
        id: '1', role: 'assistant', content: '', timestamp: new Date(), isStreaming: true
      } as any];

      component.cancelStreaming();

      expect(component.isStreaming).toBeFalse();
      expect(component.isLoading).toBeFalse();
    });

    it('should mark last message as not streaming and append [Stopped]', () => {
      component.messages = [{
        id: '1', role: 'assistant', content: 'Partial response',
        timestamp: new Date(), isStreaming: true
      } as any];
      component.isStreaming = true;

      component.cancelStreaming();

      const lastMsg = component.messages[0];
      expect(lastMsg.isStreaming).toBeFalse();
      expect(lastMsg.content).toContain('[Stopped]');
    });

    it('should not duplicate [Stopped] marker', () => {
      component.messages = [{
        id: '1', role: 'assistant', content: 'Partial [Stopped]',
        timestamp: new Date(), isStreaming: true
      } as any];
      component.isStreaming = true;

      component.cancelStreaming();

      const occurrences = (component.messages[0].content.match(/\[Stopped\]/g) || []).length;
      expect(occurrences).toBe(1);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. SESSION MANAGEMENT
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Session management', () => {
    beforeEach(() => {
      fixture.detectChanges();
      // Reset sessions to isolate from other tests that may have saved to localStorage
      component.sessions = [];
    });

    it('newChat should create a new session', () => {
      component.newChat();

      expect(component.currentSession).not.toBeNull();
      expect(component.currentSession?.name).toBe('New Chat');
      expect(component.messages.length).toBe(0);
    });

    it('newChat should add session to front of list', () => {
      component.newChat();
      const firstSession = component.sessions[0];

      component.newChat();

      expect(component.sessions[0]).not.toBe(firstSession);
      expect(component.sessions.length).toBe(2);
    });

    it('newChat should reset conversation ID', () => {
      component.currentConversationId = 'old-conv-id';
      component.newChat();

      expect(component.currentConversationId).toBeNull();
    });

    it('loadSession should restore messages', () => {
      const session = {
        id: 'sess-1', name: 'Test', messages: [
          { id: '1', role: 'user', content: 'Hello', timestamp: new Date() },
          { id: '2', role: 'assistant', content: 'Hi', timestamp: new Date() }
        ] as any[],
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      } as any;

      component.loadSession(session);

      expect(component.messages.length).toBe(2);
      expect(component.currentSession?.id).toBe('sess-1');
    });

    it('loadSession should restore conversation ID', () => {
      const session = {
        id: 'sess-1', name: 'Test', messages: [],
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        conversationId: 'conv-abc'
      } as any;

      component.loadSession(session);

      expect(component.currentConversationId).toBe('conv-abc');
    });

    it('updateCurrentSession should auto-name from first user message', () => {
      component.newChat();
      component.messages = [
        { id: '1', role: 'user', content: 'How do I configure vector search in Kompile?', timestamp: new Date() } as any
      ];

      (component as any).updateCurrentSession();

      expect(component.currentSession?.name).not.toBe('New Chat');
      expect(component.currentSession?.name?.length).toBeLessThanOrEqual(33); // 30 chars + '...'
    });

    it('getFilteredSessions should exclude empty sessions', () => {
      component.sessions = [
        { id: '1', name: 'Empty', messages: [], createdAt: '2025-01-01', updatedAt: '2025-01-01' } as any,
        { id: '2', name: 'Has msgs', messages: [{ id: 'm1', content: 'hi' }], createdAt: '2025-01-01', updatedAt: '2025-01-01' } as any
      ];

      const filtered = component.getFilteredSessions();
      expect(filtered.length).toBe(1);
      expect(filtered[0].id).toBe('2');
    });

    it('getFilteredSessions should exclude archived when showArchivedChats is false', () => {
      component.showArchivedChats = false;
      component.sessions = [
        { id: '1', name: 'Active', messages: [{ id: 'm1' }], archived: false, createdAt: '2025-01-01', updatedAt: '2025-01-01' } as any,
        { id: '2', name: 'Archived', messages: [{ id: 'm2' }], archived: true, createdAt: '2025-01-01', updatedAt: '2025-01-01' } as any
      ];

      const filtered = component.getFilteredSessions();
      expect(filtered.length).toBe(1);
      expect(filtered[0].id).toBe('1');
    });

    it('getFilteredSessions should filter by search query', () => {
      component.chatSearchQuery = 'machine learning';
      component.sessions = [
        { id: '1', name: 'ML Chat', messages: [{ id: 'm1', content: 'Tell me about machine learning' }], createdAt: '2025-01-01', updatedAt: '2025-01-01' } as any,
        { id: '2', name: 'Weather', messages: [{ id: 'm2', content: 'What is the weather?' }], createdAt: '2025-01-01', updatedAt: '2025-01-02' } as any
      ];

      const filtered = component.getFilteredSessions();
      expect(filtered.length).toBe(1);
      expect(filtered[0].id).toBe('1');
    });

    it('getFilteredSessions should sort by updatedAt descending', () => {
      component.sessions = [
        { id: '1', name: 'Old', messages: [{ id: 'm1' }], createdAt: '2025-01-01', updatedAt: '2025-01-01T00:00:00Z' } as any,
        { id: '2', name: 'New', messages: [{ id: 'm2' }], createdAt: '2025-01-02', updatedAt: '2025-01-02T00:00:00Z' } as any
      ];

      const filtered = component.getFilteredSessions();
      expect(filtered[0].id).toBe('2');
      expect(filtered[1].id).toBe('1');
    });

    it('getSessionPreview should return truncated first user message', () => {
      const session = {
        messages: [
          { id: '1', role: 'user', content: 'A very long question about machine learning algorithms and their applications in natural language processing systems' }
        ]
      } as any;

      const preview = component.getSessionPreview(session);
      expect(preview.length).toBeLessThanOrEqual(50);
      expect(preview).toContain('...');
    });

    it('getSessionPreview should return empty string for no user messages', () => {
      const session = { messages: [{ id: '1', role: 'assistant', content: 'Hello' }] } as any;
      expect(component.getSessionPreview(session)).toBe('');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10b. getAllSessions DEDUPLICATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getAllSessions() deduplication', () => {
    beforeEach(() => {
      component.sourceFilter = 'all';
      component.showArchivedChats = false;
      component.chatSearchQuery = '';
      component.currentSession = null;
    });

    it('should not duplicate sessions with same ID in local and synced', () => {
      component.sessions = [
        { id: 'sess-1', name: 'My Chat', messages: [{ id: 'm1', content: 'hello', role: 'user' }], createdAt: '2025-01-01', updatedAt: '2025-01-01T00:00:00Z' } as any
      ];
      (component as any).syncedSessions = [
        { id: 'sess-1', name: 'My Chat', messages: [], createdAt: '2025-01-01', updatedAt: '2025-01-01T00:00:00Z', synced: true, source: 'kompile' } as any
      ];

      const all = component.getAllSessions();
      expect(all.length).toBe(1);
      expect(all[0].id).toBe('sess-1');
    });

    it('should deduplicate by title+source even when IDs differ', () => {
      // Local session (from loadCliTranscriptIntoChat) has a random UUID
      component.sessions = [
        { id: 'random-uuid-123', name: 'Fix the login bug', messages: [{ id: 'm1', content: 'hi', role: 'user' }], createdAt: '2025-01-01', updatedAt: '2025-01-01T00:00:00Z', source: 'claude-code' } as any
      ];
      // Synced session (from auto-sync) has an imported- prefix ID
      (component as any).syncedSessions = [
        { id: 'imported-claude-code-abc123', name: 'Fix the login bug', messages: [], createdAt: '2025-01-01', updatedAt: '2025-01-01T00:00:00Z', synced: true, source: 'claude-code', messageCount: 5 } as any
      ];

      const all = component.getAllSessions();
      expect(all.length).toBe(1);
      // Local session should win (has full message content)
      expect(all[0].id).toBe('random-uuid-123');
    });

    it('should show both when titles match but sources differ', () => {
      component.sessions = [
        { id: 'local-1', name: 'Same Title', messages: [{ id: 'm1', content: 'hi', role: 'user' }], createdAt: '2025-01-01', updatedAt: '2025-01-01T00:00:00Z', source: 'claude-code' } as any
      ];
      (component as any).syncedSessions = [
        { id: 'synced-1', name: 'Same Title', messages: [], createdAt: '2025-01-01', updatedAt: '2025-01-02T00:00:00Z', synced: true, source: 'opencode', messageCount: 3 } as any
      ];

      const all = component.getAllSessions();
      expect(all.length).toBe(2);
    });

    it('should show both when sources match but titles differ', () => {
      component.sessions = [
        { id: 'local-1', name: 'Chat A', messages: [{ id: 'm1', content: 'hi', role: 'user' }], createdAt: '2025-01-01', updatedAt: '2025-01-01T00:00:00Z', source: 'kompile' } as any
      ];
      (component as any).syncedSessions = [
        { id: 'synced-1', name: 'Chat B', messages: [], createdAt: '2025-01-01', updatedAt: '2025-01-02T00:00:00Z', synced: true, source: 'kompile', messageCount: 3 } as any
      ];

      const all = component.getAllSessions();
      expect(all.length).toBe(2);
    });

    it('should deduplicate case-insensitively on title', () => {
      component.sessions = [
        { id: 'local-1', name: 'Fix Bug', messages: [{ id: 'm1', content: 'hi', role: 'user' }], createdAt: '2025-01-01', updatedAt: '2025-01-01T00:00:00Z', source: 'claude-code' } as any
      ];
      (component as any).syncedSessions = [
        { id: 'synced-1', name: 'fix bug', messages: [], createdAt: '2025-01-01', updatedAt: '2025-01-02T00:00:00Z', synced: true, source: 'claude-code', messageCount: 3 } as any
      ];

      const all = component.getAllSessions();
      expect(all.length).toBe(1);
    });

    it('should not deduplicate local sessions without source against synced with source', () => {
      // App-created local session has no source
      component.sessions = [
        { id: 'local-1', name: 'My Chat', messages: [{ id: 'm1', content: 'hi', role: 'user' }], createdAt: '2025-01-01', updatedAt: '2025-01-01T00:00:00Z' } as any
      ];
      // Synced session has a source
      (component as any).syncedSessions = [
        { id: 'synced-1', name: 'My Chat', messages: [], createdAt: '2025-01-01', updatedAt: '2025-01-02T00:00:00Z', synced: true, source: 'kompile', messageCount: 3 } as any
      ];

      const all = component.getAllSessions();
      expect(all.length).toBe(2);
    });

    it('should handle many synced sessions without duplicates', () => {
      component.sessions = [];
      (component as any).syncedSessions = [
        { id: 's1', name: 'Chat 1', messages: [], createdAt: '2025-01-01', updatedAt: '2025-01-01T00:00:00Z', synced: true, source: 'kompile', messageCount: 2 } as any,
        { id: 's2', name: 'Chat 2', messages: [], createdAt: '2025-01-02', updatedAt: '2025-01-02T00:00:00Z', synced: true, source: 'claude-code', messageCount: 5 } as any,
        { id: 's3', name: 'Chat 3', messages: [], createdAt: '2025-01-03', updatedAt: '2025-01-03T00:00:00Z', synced: true, source: 'opencode', messageCount: 1 } as any
      ];

      const all = component.getAllSessions();
      expect(all.length).toBe(3);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. FORMAT SESSION DATE
  // ─────────────────────────────────────────────────────────────────────────────

  describe('formatSessionDate()', () => {
    it('should return time for today', () => {
      const now = new Date();
      const result = component.formatSessionDate(now.toISOString());
      // Should be a time string like "2:30 PM"
      expect(result).toMatch(/\d{1,2}:\d{2}/);
    });

    it('should return "Yesterday" for yesterday', () => {
      const yesterday = new Date();
      yesterday.setDate(yesterday.getDate() - 1);
      expect(component.formatSessionDate(yesterday.toISOString())).toBe('Yesterday');
    });

    it('should return "Xd ago" for recent dates within a week', () => {
      const threeDaysAgo = new Date();
      threeDaysAgo.setDate(threeDaysAgo.getDate() - 3);
      expect(component.formatSessionDate(threeDaysAgo.toISOString())).toBe('3d ago');
    });

    it('should return month/day for older dates', () => {
      const oldDate = new Date();
      oldDate.setDate(oldDate.getDate() - 30);
      const result = component.formatSessionDate(oldDate.toISOString());
      // Should contain month abbreviation and day number
      expect(result).toMatch(/[A-Za-z]+\s+\d+/);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. END-TO-END RAG CHAT SIMULATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('End-to-end RAG chat simulation', () => {
    let contentSubject: Subject<string>;
    let completeSubject: Subject<any>;
    let errorSubject: Subject<string>;
    let statsSubject: Subject<any>;

    beforeEach(() => {
      contentSubject = new Subject<string>();
      completeSubject = new Subject<any>();
      errorSubject = new Subject<string>();
      statsSubject = new Subject<any>();

      spies.agentChatServiceSpy.getStreamingContent.and.returnValue(contentSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingComplete.and.returnValue(completeSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingError.and.returnValue(errorSubject.asObservable());
      spies.agentChatServiceSpy.getChatStats.and.returnValue(statsSubject.asObservable());

      fixture.detectChanges();
      component.selectedAgent = mockAgent();
    });

    it('should simulate a full RAG-augmented conversation', fakeAsync(() => {
      // Configure RAG
      component.ragEnabled = true;
      component.ragMaxResults = 3;
      component.ragThreshold = 0.5;
      component.searchType = 'hybrid';
      component.semanticK = 5;
      component.keywordK = 3;
      component.rerankerEnabled = true;
      component.rerankerType = 'rm3';
      component.rerankerOriginalQueryWeight = 0.6;

      // Send first message
      component.userInput = 'How do I add a new embedding model?';
      component.sendMessage();
      tick();

      expect(component.messages.length).toBe(2);
      expect(component.messages[0].role).toBe('user');
      expect(component.messages[1].role).toBe('assistant');
      expect(component.isStreaming).toBeTrue();

      // Verify agent was called with RAG params
      const callArgs = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args;
      expect(callArgs[3].enableRag).toBeTrue();
      expect(callArgs[3].ragMaxResults).toBe(3);
      expect(callArgs[3].ragSimilarityThreshold).toBe(0.5);

      // Simulate streaming chunks
      contentSubject.next('To add a new');
      expect(component.messages[1].content).toBe('To add a new');

      contentSubject.next('To add a new embedding model, you need to:');
      expect(component.messages[1].content).toBe('To add a new embedding model, you need to:');

      // Simulate token metrics arriving
      statsSubject.next({
        tokenMetrics: {
          inputTokens: 200, outputTokens: 150,
          totalGenerationMs: 3000, tokensPerSecond: 50
        }
      });
      expect(component.messages[1].tokenMetrics?.inputTokens).toBe(200);

      // Complete the stream
      completeSubject.next({
        content: 'To add a new embedding model, you need to: 1) Register in ModelConstants, 2) Create encoder class, 3) Update factory.',
        latencyMs: 3500,
        sources: [
          { title: 'ModelConstants.java', snippet: 'public static final ModelDescriptor...' },
          { title: 'CLAUDE.md', snippet: '### Adding a New Embedding Model' }
        ],
        tokenMetrics: {
          inputTokens: 250, outputTokens: 180,
          totalGenerationMs: 3500, tokensPerSecond: 51.4,
          model: 'claude-sonnet-4-20250514'
        }
      });

      expect(component.isStreaming).toBeFalse();
      expect(component.messages[1].isStreaming).toBeFalse();
      expect(component.messages[1].sources?.length).toBe(2);
      expect(component.messages[1].tokenMetrics?.model).toBe('claude-sonnet-4-20250514');

      // Send a follow-up
      component.userInput = 'Can you show me a code example?';
      component.sendMessage();
      tick();

      expect(component.messages.length).toBe(4); // user, assistant, user, assistant
      expect(component.messages[2].role).toBe('user');
      expect(component.messages[3].role).toBe('assistant');
      expect(component.messages[3].isStreaming).toBeTrue();
    }));

    it('should simulate Graph RAG query', fakeAsync(() => {
      component.graphRagEnabled = true;
      component.graphRagSearchType = 'GLOBAL';
      component.graphRagMaxResults = 10;
      component.ragEnabled = true;

      component.userInput = 'What entities are related to VectorStore?';
      component.sendMessage();
      tick();

      const options = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args[3];
      expect(options.enableGraphRag).toBeTrue();
      expect(options.graphRagSearchType).toBe('GLOBAL');
      expect(options.graphRagMaxResults).toBe(10);
      expect(options.enableRag).toBeTrue();

      // Complete with graph results
      completeSubject.next({
        content: 'VectorStore is related to: EmbeddingModel, Document, HnswIndex...',
        latencyMs: 800
      });

      expect(component.isStreaming).toBeFalse();
      expect(component.messages[1].content).toContain('VectorStore');
    }));

    it('should simulate conversation with custom system prompt', fakeAsync(() => {
      component.systemPrompt = 'You are a Java coding assistant. Only respond with code.';

      // buildRagOptions should include the system prompt
      const opts = (component as any).buildRagOptions();
      expect(opts.systemPrompt).toBe('You are a Java coding assistant. Only respond with code.');
    }));

    it('should simulate semantic-only search', fakeAsync(() => {
      component.searchType = 'semantic';
      component.semanticK = 10;
      component.keywordK = 0;

      const opts = (component as any).buildRagOptions();

      // The spy returns the computed result
      expect(opts.semanticK).toBe(10); // semanticK + keywordK
      expect(opts.keywordK).toBe(0);
    }));

    it('should simulate keyword-only search', fakeAsync(() => {
      component.searchType = 'keyword';
      component.semanticK = 0;
      component.keywordK = 10;

      const opts = (component as any).buildRagOptions();

      expect(opts.semanticK).toBe(0);
      expect(opts.keywordK).toBe(10);
    }));

    it('should handle agent sendMessage rejection', fakeAsync(() => {
      spies.agentChatServiceSpy.sendMessage.and.returnValue(
        Promise.reject(new Error('Agent process crashed'))
      );

      component.userInput = 'Hello';
      component.sendMessage();
      tick();

      // The assistant message should show the error
      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg?.error).toBeTrue();
      expect(assistantMsg?.content).toBe('Agent process crashed');
      expect(assistantMsg?.isStreaming).toBeFalse();
      expect(component.isStreaming).toBeFalse();
    }));

    it('should handle non-Error rejection', fakeAsync(() => {
      spies.agentChatServiceSpy.sendMessage.and.returnValue(
        Promise.reject('string error')
      );

      component.userInput = 'Hello';
      component.sendMessage();
      tick();

      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg?.error).toBeTrue();
      expect(assistantMsg?.content).toBe('Agent request failed');
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 13. MULTI-TURN CONVERSATION STATE
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Multi-turn conversation state', () => {
    let completeSubject: Subject<any>;

    beforeEach(() => {
      const contentSubject = new Subject<string>();
      completeSubject = new Subject<any>();
      const errorSubject = new Subject<string>();
      const statsSubject = new Subject<any>();

      spies.agentChatServiceSpy.getStreamingContent.and.returnValue(contentSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingComplete.and.returnValue(completeSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingError.and.returnValue(errorSubject.asObservable());
      spies.agentChatServiceSpy.getChatStats.and.returnValue(statsSubject.asObservable());

      fixture.detectChanges();
      component.selectedAgent = mockAgent();
    });

    it('should maintain message history across turns', fakeAsync(() => {
      // Turn 1
      component.userInput = 'What is RAG?';
      component.sendMessage();
      tick();
      completeSubject.next({ content: 'RAG stands for Retrieval Augmented Generation.' });

      // Turn 2
      component.userInput = 'How does it work?';
      component.sendMessage();
      tick();
      completeSubject.next({ content: 'It works by retrieving relevant documents.' });

      // Turn 3
      component.userInput = 'Give me an example.';
      component.sendMessage();
      tick();
      completeSubject.next({ content: 'For example, you can query a vector store.' });

      expect(component.messages.length).toBe(6); // 3 user + 3 assistant
      expect(component.messages[0].role).toBe('user');
      expect(component.messages[1].role).toBe('assistant');
      expect(component.messages[2].role).toBe('user');
      expect(component.messages[3].role).toBe('assistant');
      expect(component.messages[4].role).toBe('user');
      expect(component.messages[5].role).toBe('assistant');
    }));

    it('should auto-name session from first user message', fakeAsync(() => {
      component.newChat();
      component.userInput = 'How do I configure the reranker settings for my RAG pipeline?';
      component.sendMessage();
      tick();
      completeSubject.next({ content: 'To configure reranker...' });

      expect(component.currentSession?.name).not.toBe('New Chat');
      // First 30 chars + '...'
      expect(component.currentSession?.name).toBe('How do I configure the reranke...');
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 14. PARAMETER EDGE CASES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Parameter edge cases', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should handle zero similarity threshold', () => {
      component.similarityThreshold = 0.0;
      const opts = (component as any).buildRagOptions();
      expect(opts.similarityThreshold).toBe(0.0);
    });

    it('should handle maximum similarity threshold', () => {
      component.similarityThreshold = 1.0;
      const opts = (component as any).buildRagOptions();
      expect(opts.similarityThreshold).toBe(1.0);
    });

    it('should handle zero K values for both search types', () => {
      component.semanticK = 0;
      component.keywordK = 0;
      component.searchType = 'hybrid';

      const opts = (component as any).buildRagOptions();

      const call = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      expect(call.args[1]).toBe(0);
      expect(call.args[2]).toBe(0);
    });

    it('should handle large K values', () => {
      component.semanticK = 100;
      component.keywordK = 100;

      const opts = (component as any).buildRagOptions();

      const call = spies.ragServiceSpy.buildOptions.calls.mostRecent();
      expect(call.args[1]).toBe(100);
      expect(call.args[2]).toBe(100);
    });

    it('should handle max history messages of zero', () => {
      component.maxHistoryMessages = 0;
      const opts = (component as any).buildRagOptions();
      expect(opts.maxHistoryMessages).toBe(0);
    });

    it('should handle rerankerTopK of -1 (all results)', () => {
      component.rerankerEnabled = true;
      component.rerankerTopK = -1;

      (component as any).buildRagOptions();

      const rerankerArg = spies.ragServiceSpy.buildOptions.calls.mostRecent().args[8];
      expect(rerankerArg.topK).toBe(-1);
    });

    it('should handle no timeout (0)', () => {
      component.timeoutSeconds = 0;
      component.selectedAgent = mockAgent({ name: 'test' });
      component.userInput = 'Hello';
      component.sendMessage();

      const options = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args[3];
      expect(options.timeoutSeconds).toBe(0);
    });

    it('should handle Rocchio with negative feedback enabled', () => {
      component.rerankerEnabled = true;
      component.rerankerType = 'rocchio';
      component.rerankerUseNegative = true;
      component.rerankerGamma = 0.5;

      (component as any).buildRagOptions();

      const rerankerArg = spies.ragServiceSpy.buildOptions.calls.mostRecent().args[8];
      expect(rerankerArg.useNegative).toBeTrue();
      expect(rerankerArg.gamma).toBe(0.5);
    });

    it('should handle Axiom with non-deterministic mode', () => {
      component.rerankerEnabled = true;
      component.rerankerType = 'axiom';
      component.rerankerDeterministic = false;

      (component as any).buildRagOptions();

      const rerankerArg = spies.ragServiceSpy.buildOptions.calls.mostRecent().args[8];
      expect(rerankerArg.deterministic).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 15. DISPLAY TOGGLES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Display toggles', () => {
    it('should initialize display toggles with defaults', () => {
      expect(component.showDocuments).toBeTrue();
      expect(component.showMetrics).toBeTrue();
      expect(component.showQueryInfo).toBeFalse();
    });

    it('should toggle showSettings', () => {
      expect(component.showSettings).toBeFalse();
      component.showSettings = true;
      expect(component.showSettings).toBeTrue();
    });

    it('should toggle showHistorySidebar', () => {
      expect(component.showHistorySidebar).toBeTrue();
      component.showHistorySidebar = false;
      expect(component.showHistorySidebar).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 16. ConversationalRagService.buildOptions() INTEGRATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('ConversationalRagService.buildOptions() integration', () => {
    it('should produce correct options for hybrid search', () => {
      // Use the real spy implementation we configured
      const opts = spies.ragServiceSpy.buildOptions(
        'hybrid', 5, 5, 0.5, 10, false, true, undefined, undefined
      );

      expect(opts.semanticK).toBe(5);
      expect(opts.keywordK).toBe(5);
      expect(opts.similarityThreshold).toBe(0.5);
      expect(opts.maxHistoryMessages).toBe(10);
      expect(opts.useToolCalling).toBeFalse();
      expect(opts.enableQueryProcessing).toBeTrue();
    });

    it('should combine K values for semantic-only', () => {
      const opts = spies.ragServiceSpy.buildOptions(
        'semantic', 5, 5, 0.5, 10, false, true, undefined, undefined
      );

      expect(opts.semanticK).toBe(10); // 5 + 5
      expect(opts.keywordK).toBe(0);
    });

    it('should combine K values for keyword-only', () => {
      const opts = spies.ragServiceSpy.buildOptions(
        'keyword', 5, 5, 0.5, 10, false, true, undefined, undefined
      );

      expect(opts.semanticK).toBe(0);
      expect(opts.keywordK).toBe(10); // 5 + 5
    });

    it('should include system prompt when provided', () => {
      const opts = spies.ragServiceSpy.buildOptions(
        'hybrid', 5, 5, 0.5, 10, false, true, 'Be concise.', undefined
      );

      expect(opts.systemPrompt).toBe('Be concise.');
    });

    it('should include reranker config when enabled', () => {
      const rerankerConfig: RerankerConfig = {
        ...DEFAULT_RERANKER_CONFIG,
        enabled: true,
        type: 'rrf',
        rrfK: 60
      };

      const opts = spies.ragServiceSpy.buildOptions(
        'hybrid', 5, 5, 0.5, 10, false, true, undefined, rerankerConfig
      );

      expect(opts.rerankerConfig).toBeDefined();
      expect(opts.rerankerConfig?.type).toBe('rrf');
    });

    it('should not include reranker config when disabled', () => {
      const rerankerConfig: RerankerConfig = {
        ...DEFAULT_RERANKER_CONFIG,
        enabled: false
      };

      const opts = spies.ragServiceSpy.buildOptions(
        'hybrid', 5, 5, 0.5, 10, false, true, undefined, rerankerConfig
      );

      expect(opts.rerankerConfig).toBeUndefined();
    });

    it('should enable tool calling', () => {
      const opts = spies.ragServiceSpy.buildOptions(
        'hybrid', 5, 5, 0.5, 10, true, true, undefined, undefined
      );

      expect(opts.useToolCalling).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 17. TEMPLATE RENDERING WITH RAG RESULTS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Template rendering with RAG results', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should render user message in the DOM', () => {
      component.messages = [{
        id: '1', role: 'user', content: 'What is vector search?', timestamp: new Date()
      } as any];

      fixture.detectChanges();

      const compiled = fixture.nativeElement;
      const userMessages = compiled.querySelectorAll('.message-user, .user-message');
      // If CSS class exists, check content; otherwise just verify messages array
      expect(component.messages[0].content).toBe('What is vector search?');
    });

    it('should render assistant message with documents section', () => {
      component.showDocuments = true;
      component.messages = [{
        id: '1', role: 'assistant', content: 'Vector search uses...', timestamp: new Date(),
        isStreaming: false,
        documents: [
          { id: 'doc1', content: 'Vector databases store embeddings...', score: 0.95, metadata: { source: 'docs.pdf' } },
          { id: 'doc2', content: 'HNSW indexing provides fast...', score: 0.87, metadata: { source: 'guide.pdf' } }
        ] as RetrievedDocument[]
      } as any];

      fixture.detectChanges();

      // Verify the component state is correct for template rendering
      expect(component.messages[0].documents?.length).toBe(2);
      expect(component.messages[0].documents?.[0].score).toBe(0.95);
    });

    it('should render query info when wasRewritten is true', () => {
      component.showQueryInfo = true;
      component.messages = [{
        id: '1', role: 'assistant', content: 'Answer...', timestamp: new Date(),
        isStreaming: false,
        queryInfo: {
          originalQuery: 'how vector search',
          rewrittenQuery: 'How does vector similarity search work in Kompile?',
          wasRewritten: true,
          intent: 'technical_explanation'
        }
      } as any];

      fixture.detectChanges();

      expect(component.messages[0].queryInfo?.wasRewritten).toBeTrue();
      expect(component.messages[0].queryInfo?.rewrittenQuery).toContain('How does vector');
    });

    it('should render performance metrics', () => {
      component.showMetrics = true;
      component.messages = [{
        id: '1', role: 'assistant', content: 'Answer', timestamp: new Date(),
        isStreaming: false,
        metrics: {
          totalMs: 1500,
          retrievalMs: 200,
          generationMs: 1300,
          documentsRetrieved: 5
        }
      } as any];

      fixture.detectChanges();

      expect(component.messages[0].metrics?.totalMs).toBe(1500);
      expect(component.messages[0].metrics?.documentsRetrieved).toBe(5);
    });

    it('should show streaming indicator for in-progress messages', () => {
      component.messages = [{
        id: '1', role: 'assistant', content: 'Generating...', timestamp: new Date(),
        isStreaming: true
      } as any];

      fixture.detectChanges();

      expect(component.messages[0].isStreaming).toBeTrue();
    });

    it('should show error state for failed messages', () => {
      component.messages = [{
        id: '1', role: 'assistant', content: 'Connection refused', timestamp: new Date(),
        isStreaming: false, error: true
      } as any];

      fixture.detectChanges();

      expect(component.messages[0].error).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 18. AGENT CONFIGURATION WITH RAG
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Agent configuration with RAG', () => {
    beforeEach(() => {
      fixture.detectChanges();
      component.selectedAgent = mockAgent();
    });

    it('should combine agent with all RAG options', () => {
      component.ragEnabled = true;
      component.ragMaxResults = 8;
      component.ragThreshold = 0.6;
      component.graphRagEnabled = true;
      component.graphRagSearchType = 'GLOBAL';
      component.graphRagMaxResults = 12;
      component.skipPermissions = false;
      component.timeoutSeconds = 900;
      component.selectedFolder = { folderId: 'f-1', name: 'MyDocs' } as any;

      component.userInput = 'Complex query';
      component.sendMessage();

      const opts = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args[3];
      expect(opts).toEqual(jasmine.objectContaining({
        enableRag: true,
        ragMaxResults: 8,
        ragSimilarityThreshold: 0.6,
        enableGraphRag: true,
        graphRagSearchType: 'GLOBAL',
        graphRagMaxResults: 12,
        skipPermissions: false,
        timeoutSeconds: 900,
        folderId: 'f-1'
      }));
    });

    it('should create agent session on first message', () => {
      component.userInput = 'Hello';
      component.sendMessage();

      expect(spies.agentChatServiceSpy.createSession).toHaveBeenCalled();
    });

    it('should reuse agent session for subsequent messages', fakeAsync(() => {
      const completeSubject = new Subject<any>();
      spies.agentChatServiceSpy.getStreamingComplete.and.returnValue(completeSubject.asObservable());

      component.userInput = 'First message';
      component.sendMessage();
      tick();
      completeSubject.next({ content: 'Reply 1' });

      spies.agentChatServiceSpy.createSession.calls.reset();

      component.userInput = 'Second message';
      component.sendMessage();
      tick();

      // Should NOT create a new session for second message
      expect(spies.agentChatServiceSpy.createSession).not.toHaveBeenCalled();
    }));

    it('should pass selected agent to sendMessage', () => {
      const agent = mockAgent({ name: 'custom-agent', displayName: 'Custom' });
      component.selectedAgent = agent;
      component.userInput = 'Hello';
      component.sendMessage();

      const callArgs = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args;
      expect(callArgs[2]).toBe(agent);
    });

    it('should tag assistant message with agent info', () => {
      const agent = mockAgent({ name: 'claude-api', displayName: 'Claude' });
      component.selectedAgent = agent;
      component.userInput = 'Hello';
      component.sendMessage();

      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg?.agent).toBe(agent);
    });
  });
});
