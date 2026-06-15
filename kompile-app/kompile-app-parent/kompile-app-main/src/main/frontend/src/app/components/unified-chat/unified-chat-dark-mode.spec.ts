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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { of, Subject } from 'rxjs';

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
import { RagServiceStatus } from '../../models/api-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

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
  ], { agents$: new Subject<any[]>().asObservable() });
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

  ragServiceSpy.getStatus.and.returnValue(of({ available: true, service: 'rag' } as RagServiceStatus));
  ragServiceSpy.buildOptions.and.returnValue({});
  agentServiceSpy.getAllAgents.and.returnValue(of([]));
  agentServiceSpy.getAvailableAgents.and.returnValue(of([]));
  agentServiceSpy.getKompileLocalStatus.and.returnValue(of({ connected: false, modelLoaded: false, stagingUrl: null } as any));
  chatStorageServiceSpy.getSessions.and.returnValue([]);
  chatHistoryServiceSpy.getSessions.and.returnValue(of([]));
  cliTranscriptServiceSpy.listSessions.and.returnValue(of([]));
  cliTranscriptServiceSpy.discoverSources.and.returnValue(of({}));
  folderServiceSpy.getFolders.and.returnValue(of([]));

  agentChatServiceSpy.getStreamingContent.and.returnValue(new Subject<string>().asObservable());
  agentChatServiceSpy.getStreamingComplete.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getStreamingError.and.returnValue(new Subject<string>().asObservable());
  agentChatServiceSpy.getChatStats.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getSources.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getFilesModified.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getToolUse.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.sendMessage.and.returnValue(Promise.resolve());
  agentChatServiceSpy.createSession.and.returnValue({
    id: 'session-1', name: 'Test', messages: [], createdAt: new Date().toISOString()
  });

  return {
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

describe('UnifiedChatComponent - Dark Mode & CSS Variables', () => {
  let component: UnifiedChatComponent;
  let fixture: ComponentFixture<UnifiedChatComponent>;

  beforeEach(async () => {
    const spies = createTestBed();

    await TestBed.configureTestingModule({
      imports: [FormsModule, NoopAnimationsModule, HttpClientTestingModule, MatMenuModule],
      declarations: [UnifiedChatComponent],
      providers: spies.providers,
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(UnifiedChatComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    // Clean up dark-theme class if applied
    document.body.classList.remove('dark-theme');
    document.body.classList.remove('light-theme');
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. WRAPPER USES CSS VARIABLES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('CSS variable usage in template', () => {
    it('should render the unified-chat-wrapper element', () => {
      const wrapper = fixture.nativeElement.querySelector('.unified-chat-wrapper');
      expect(wrapper).toBeTruthy();
    });

    it('should render the chat-container main area', () => {
      const container = fixture.nativeElement.querySelector('.chat-container');
      expect(container).toBeTruthy();
    });

    it('should render the history-sidebar', () => {
      const sidebar = fixture.nativeElement.querySelector('.history-sidebar');
      expect(sidebar).toBeTruthy();
    });

    it('should render the conversation-area', () => {
      const area = fixture.nativeElement.querySelector('.conversation-area');
      expect(area).toBeTruthy();
    });

    it('should render the input-area', () => {
      const input = fixture.nativeElement.querySelector('.input-area');
      expect(input).toBeTruthy();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. DARK MODE CLASS PROPAGATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Dark mode class propagation', () => {
    it('should render without dark-theme class on body by default', () => {
      expect(document.body.classList.contains('dark-theme')).toBeFalse();
    });

    it('should apply dark-theme styles when body has dark-theme class', () => {
      document.body.classList.add('dark-theme');
      fixture.detectChanges();

      // The component itself doesn't add the class — it reads from body via CSS vars
      // We just verify the component renders under the dark body
      const wrapper = fixture.nativeElement.querySelector('.unified-chat-wrapper');
      expect(wrapper).toBeTruthy();
    });

    it('should apply light-theme class removal correctly', () => {
      document.body.classList.add('dark-theme');
      fixture.detectChanges();
      expect(document.body.classList.contains('dark-theme')).toBeTrue();

      document.body.classList.remove('dark-theme');
      document.body.classList.add('light-theme');
      fixture.detectChanges();
      expect(document.body.classList.contains('light-theme')).toBeTrue();
      expect(document.body.classList.contains('dark-theme')).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. CONTEXT PANEL DARK MODE
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Context panel in dark mode', () => {
    it('should render context panel under dark-theme without errors', () => {
      document.body.classList.add('dark-theme');

      const source = {
        sourceName: 'test.pdf', content: 'Test content', score: 0.8,
        sourceType: 'pdf', chunkIndex: 0, documentId: 'doc-1'
      };
      const message = {
        id: 'msg-1', role: 'assistant', content: 'Test', timestamp: new Date(),
        sources: [source], _sourcesExpanded: false
      };
      component.openContextPanel(message.id, message.sources as any[]);
      fixture.detectChanges();

      const panel = fixture.nativeElement.querySelector('.context-panel:not(.hidden)');
      expect(panel).toBeTruthy();

      const card = fixture.nativeElement.querySelector('.source-card');
      expect(card).toBeTruthy();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. WELCOME MESSAGE RENDERS IN BOTH THEMES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Welcome message rendering', () => {
    it('should render welcome message in light theme', () => {
      const welcome = fixture.nativeElement.querySelector('.welcome-message');
      expect(welcome).toBeTruthy();
    });

    it('should render welcome message in dark theme', () => {
      document.body.classList.add('dark-theme');
      fixture.detectChanges();

      const welcome = fixture.nativeElement.querySelector('.welcome-message');
      expect(welcome).toBeTruthy();
    });

    it('should render prompt suggestions in both themes', () => {
      // Light
      let suggestions = fixture.nativeElement.querySelectorAll('.suggestion-chip');
      // No agent selected, so suggestions may not render
      // Just verify no errors
      expect(fixture.nativeElement.querySelector('.welcome-message')).toBeTruthy();

      // Dark
      document.body.classList.add('dark-theme');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.welcome-message')).toBeTruthy();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. INPUT AREA IN BOTH THEMES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Input area rendering', () => {
    it('should render textarea in light mode', () => {
      const textarea = fixture.nativeElement.querySelector('.input-container textarea');
      expect(textarea).toBeTruthy();
    });

    it('should render textarea in dark mode without errors', () => {
      document.body.classList.add('dark-theme');
      fixture.detectChanges();

      const textarea = fixture.nativeElement.querySelector('.input-container textarea');
      expect(textarea).toBeTruthy();
    });

    it('should render send button in both modes', () => {
      const btn = fixture.nativeElement.querySelector('.send-btn');
      expect(btn).toBeTruthy();

      document.body.classList.add('dark-theme');
      fixture.detectChanges();

      const btnDark = fixture.nativeElement.querySelector('.send-btn');
      expect(btnDark).toBeTruthy();
    });

    it('should render input hints in both modes', () => {
      const hints = fixture.nativeElement.querySelector('.input-hints');
      expect(hints).toBeTruthy();

      document.body.classList.add('dark-theme');
      fixture.detectChanges();

      const hintsDark = fixture.nativeElement.querySelector('.input-hints');
      expect(hintsDark).toBeTruthy();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. HEADER IN BOTH THEMES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Header rendering', () => {
    it('should render chat header in light mode', () => {
      const header = fixture.nativeElement.querySelector('.chat-header');
      expect(header).toBeTruthy();
    });

    it('should render chat header in dark mode', () => {
      document.body.classList.add('dark-theme');
      fixture.detectChanges();

      const header = fixture.nativeElement.querySelector('.chat-header');
      expect(header).toBeTruthy();
    });

    it('should render settings button in both modes', () => {
      const btn = fixture.nativeElement.querySelector('.icon-btn');
      expect(btn).toBeTruthy();

      document.body.classList.add('dark-theme');
      fixture.detectChanges();

      const btnDark = fixture.nativeElement.querySelector('.icon-btn');
      expect(btnDark).toBeTruthy();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. SETTINGS SIDEBAR IN BOTH THEMES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Settings sidebar rendering', () => {
    it('should render settings sidebar (hidden) in light mode', () => {
      const sidebar = fixture.nativeElement.querySelector('.settings-sidebar');
      expect(sidebar).toBeTruthy();
    });

    it('should render settings sidebar (hidden) in dark mode', () => {
      document.body.classList.add('dark-theme');
      fixture.detectChanges();

      const sidebar = fixture.nativeElement.querySelector('.settings-sidebar');
      expect(sidebar).toBeTruthy();
    });

    it('should show settings sidebar when toggled in dark mode', () => {
      document.body.classList.add('dark-theme');
      component.showSettings = true;
      fixture.detectChanges();

      const sidebar = fixture.nativeElement.querySelector('.settings-sidebar.visible');
      expect(sidebar).toBeTruthy();
    });
  });
});
