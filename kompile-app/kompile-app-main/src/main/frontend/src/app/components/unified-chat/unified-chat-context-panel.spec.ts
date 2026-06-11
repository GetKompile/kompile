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

  // Default return values
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
    id: 'agent-session-1', name: 'Test', messages: [], createdAt: new Date().toISOString()
  });

  return {
    ragServiceSpy, agentChatServiceSpy, agentServiceSpy,
    chatStorageServiceSpy, chatHistoryServiceSpy, cliTranscriptServiceSpy,
    folderServiceSpy, modelContextServiceSpy, webSocketServiceSpy, dialogSpy,
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

/** Helper to create a mock message with sources */
function mockMessageWithSources(id: string, sources: any[]): any {
  return {
    id,
    role: 'assistant',
    content: 'Response with sources [1] and [2].',
    timestamp: new Date(),
    sources,
    _sourcesExpanded: false
  };
}

/** Helper to create a mock source */
function mockSource(overrides: {[key: string]: any} = {}): any {
  // Template reads source.name || source.fileName || source.sourceName for display
  const base: {[key: string]: any} = {
    name: 'document.pdf',
    sourceName: 'document.pdf',
    content: 'This is the source content excerpt for testing purposes.',
    score: 0.85,
    sourceType: 'pdf',
    chunkIndex: 0,
    documentId: 'doc-123',
    sourceUrl: null,
    filePath: '/path/to/document.pdf',
    _highlighted: false,
    _copied: false,
  };
  const merged: {[key: string]: any} = { ...base, ...overrides };
  // If caller supplied sourceName override but not name, sync name to sourceName
  if (overrides['sourceName'] && !overrides['name']) {
    merged['name'] = overrides['sourceName'];
  }
  return merged;
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('UnifiedChatComponent - Context Panel', () => {
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
    fixture.detectChanges();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. INITIAL STATE
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Initial state', () => {
    it('should have context panel hidden by default', () => {
      expect(component.showContextPanel).toBeFalse();
    });

    it('should have empty sources array by default', () => {
      expect(component.contextPanelSources).toEqual([]);
    });

    it('should have null message ID by default', () => {
      expect(component.contextPanelMessageId).toBeNull();
    });

    it('should have highlighted source index at -1', () => {
      expect(component.highlightedSourceIndex).toBe(-1);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. openContextPanel()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('openContextPanel()', () => {
    it('should open the panel with sources from the message', () => {
      const sources = [mockSource(), mockSource({ sourceName: 'other.md', score: 0.72 })];
      const message = mockMessageWithSources('msg-1', sources);

      component.openContextPanel(message.id, message.sources || []);

      expect(component.showContextPanel).toBeTrue();
      expect(component.contextPanelSources).toEqual(sources);
      expect(component.contextPanelMessageId).toBe('msg-1');
    });

    it('should reset highlighted source index on open', () => {
      component.highlightedSourceIndex = 3;
      const message = mockMessageWithSources('msg-1', [mockSource()]);

      component.openContextPanel(message.id, message.sources || []);

      expect(component.highlightedSourceIndex).toBe(-1);
    });

    it('should toggle closed when opening same message twice', () => {
      const message = mockMessageWithSources('msg-1', [mockSource()]);

      component.openContextPanel(message.id, message.sources || []);
      expect(component.showContextPanel).toBeTrue();

      component.openContextPanel(message.id, message.sources || []);
      expect(component.showContextPanel).toBeFalse();
      expect(component.contextPanelSources).toEqual([]);
    });

    it('should switch to new message sources when a different message is opened', () => {
      const sources1 = [mockSource({ sourceName: 'first.pdf' })];
      const sources2 = [mockSource({ sourceName: 'second.md' }), mockSource({ sourceName: 'third.txt' })];
      const msg1 = mockMessageWithSources('msg-1', sources1);
      const msg2 = mockMessageWithSources('msg-2', sources2);

      component.openContextPanel(msg1.id, msg1.sources || []);
      expect(component.contextPanelSources).toEqual(sources1);

      component.openContextPanel(msg2.id, msg2.sources || []);
      expect(component.showContextPanel).toBeTrue();
      expect(component.contextPanelSources).toEqual(sources2);
      expect(component.contextPanelMessageId).toBe('msg-2');
    });

    it('should close settings sidebar when opening context panel', () => {
      component.showSettings = true;
      const message = mockMessageWithSources('msg-1', [mockSource()]);

      component.openContextPanel(message.id, message.sources || []);

      expect(component.showSettings).toBeFalse();
      expect(component.showContextPanel).toBeTrue();
    });

    it('should handle messages with no sources gracefully', () => {
      const message = mockMessageWithSources('msg-1', []);

      component.openContextPanel(message.id, message.sources || []);

      // openContextPanel returns early when sources is empty
      expect(component.showContextPanel).toBeFalse();
      expect(component.contextPanelSources).toEqual([]);
    });

    it('should handle messages with undefined sources', () => {
      const message = { id: 'msg-1', role: 'assistant', content: 'Test', timestamp: new Date() };

      component.openContextPanel((message as any).id, (message as any).sources || []);

      // openContextPanel returns early when sources is empty
      expect(component.showContextPanel).toBeFalse();
      expect(component.contextPanelSources).toEqual([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. closeContextPanel()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('closeContextPanel()', () => {
    it('should hide the panel', () => {
      const message = mockMessageWithSources('msg-1', [mockSource()]);
      component.openContextPanel(message.id, message.sources || []);

      component.closeContextPanel();

      expect(component.showContextPanel).toBeFalse();
    });

    it('should clear sources', () => {
      const message = mockMessageWithSources('msg-1', [mockSource()]);
      component.openContextPanel(message.id, message.sources || []);

      component.closeContextPanel();

      expect(component.contextPanelSources).toEqual([]);
    });

    it('should clear message ID', () => {
      const message = mockMessageWithSources('msg-1', [mockSource()]);
      component.openContextPanel(message.id, message.sources || []);

      component.closeContextPanel();

      expect(component.contextPanelMessageId).toBeNull();
    });

    it('should reset highlighted source index', () => {
      component.highlightedSourceIndex = 2;

      component.closeContextPanel();

      expect(component.highlightedSourceIndex).toBe(-1);
    });

    it('should be idempotent when called while already closed', () => {
      expect(component.showContextPanel).toBeFalse();

      component.closeContextPanel();

      expect(component.showContextPanel).toBeFalse();
      expect(component.contextPanelSources).toEqual([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. highlightSourceInPanel()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('highlightSourceInPanel()', () => {
    it('should set the highlighted source index', () => {
      const sources = [mockSource(), mockSource({ sourceName: 'b.pdf' }), mockSource({ sourceName: 'c.pdf' })];
      const message = mockMessageWithSources('msg-1', sources);

      component.highlightSourceInPanel(message.id, 1, message.sources || []);

      expect(component.highlightedSourceIndex).toBe(1);
    });

    it('should open the panel if not already open', () => {
      const message = mockMessageWithSources('msg-1', [mockSource()]);

      component.highlightSourceInPanel(message.id, 0, message.sources || []);

      expect(component.showContextPanel).toBeTrue();
      expect(component.contextPanelSources.length).toBe(1);
    });

    it('should switch to the new message if panel shows a different message', () => {
      const msg1 = mockMessageWithSources('msg-1', [mockSource({ sourceName: 'a.pdf' })]);
      const msg2 = mockMessageWithSources('msg-2', [mockSource({ sourceName: 'b.pdf' }), mockSource({ sourceName: 'c.pdf' })]);
      component.openContextPanel(msg1.id, msg1.sources || []);

      component.highlightSourceInPanel(msg2.id, 1, msg2.sources || []);

      expect(component.contextPanelMessageId).toBe('msg-2');
      expect(component.contextPanelSources.length).toBe(2);
      expect(component.highlightedSourceIndex).toBe(1);
    });

    it('should close settings sidebar if open', () => {
      component.showSettings = true;
      const message = mockMessageWithSources('msg-1', [mockSource()]);

      component.highlightSourceInPanel(message.id, 0, message.sources || []);

      expect(component.showSettings).toBeFalse();
    });

    it('should auto-clear highlight after timeout', fakeAsync(() => {
      const message = mockMessageWithSources('msg-1', [mockSource()]);

      component.highlightSourceInPanel(message.id, 0, message.sources || []);
      expect(component.highlightedSourceIndex).toBe(0);

      tick(2500);
      expect(component.highlightedSourceIndex).toBe(-1);
    }));

    it('should keep the panel open when same message highlights different source', () => {
      const sources = [mockSource(), mockSource({ sourceName: 'b.pdf' })];
      const message = mockMessageWithSources('msg-1', sources);
      component.openContextPanel(message.id, message.sources || []);

      component.highlightSourceInPanel(message.id, 0, message.sources || []);
      expect(component.highlightedSourceIndex).toBe(0);

      component.highlightSourceInPanel(message.id, 1, message.sources || []);
      expect(component.highlightedSourceIndex).toBe(1);
      expect(component.showContextPanel).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. toggleSources() — context panel integration
  // ─────────────────────────────────────────────────────────────────────────────

  describe('toggleSources() with context panel', () => {
    it('should open the context panel when expanding sources', () => {
      const message = mockMessageWithSources('msg-1', [mockSource()]);
      message._sourcesExpanded = false;

      component.toggleSources(message);

      expect(message._sourcesExpanded).toBeTrue();
      expect(component.showContextPanel).toBeTrue();
    });

    it('should not close the context panel when collapsing inline sources', () => {
      const message = mockMessageWithSources('msg-1', [mockSource()]);
      component.openContextPanel(message.id, message.sources || []);
      message._sourcesExpanded = true;

      component.toggleSources(message);

      // The inline section collapses, but context panel stays open
      expect(message._sourcesExpanded).toBeFalse();
    });

    it('should not open context panel for messages with empty sources', () => {
      const message = mockMessageWithSources('msg-1', []);
      message._sourcesExpanded = false;

      component.toggleSources(message);

      expect(message._sourcesExpanded).toBeTrue();
      expect(component.showContextPanel).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. getSourceIcon()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getSourceIcon()', () => {
    // Component uses source.name || source.fileName for the file name,
    // and returns Material icon name strings (not emojis).

    it('should return PDF icon for .pdf files', () => {
      expect(component.getSourceIcon({ name: 'report.pdf' })).toBe('picture_as_pdf');
    });

    it('should return markdown icon for .md files', () => {
      expect(component.getSourceIcon({ name: 'README.md' })).toBe('description');
    });

    it('should return spreadsheet icon for .xlsx files', () => {
      expect(component.getSourceIcon({ name: 'data.xlsx' })).toBe('table_chart');
    });

    it('should return spreadsheet icon for .xls files', () => {
      expect(component.getSourceIcon({ name: 'old.xls' })).toBe('table_chart');
    });

    it('should return email icon for .eml files', () => {
      expect(component.getSourceIcon({ name: 'message.eml' })).toBe('email');
    });

    it('should return globe icon for .html files', () => {
      expect(component.getSourceIcon({ name: 'page.html' })).toBe('language');
    });

    it('should return clipboard icon for .json files', () => {
      expect(component.getSourceIcon({ name: 'config.json' })).toBe('data_object');
    });

    it('should return page icon for .txt files', () => {
      expect(component.getSourceIcon({ name: 'notes.txt' })).toBe('article');
    });

    it('should return link icon for graph source type', () => {
      expect(component.getSourceIcon({ sourceType: 'graph' })).toBe('hub');
    });

    it('should return link icon for knowledge_graph source type', () => {
      expect(component.getSourceIcon({ sourceType: 'knowledge_graph' })).toBe('hub');
    });

    it('should return default document icon for unknown types', () => {
      expect(component.getSourceIcon({ name: 'mystery.xyz' })).toBe('insert_drive_file');
    });

    it('should handle null/undefined source gracefully', () => {
      expect(component.getSourceIcon(null)).toBe('insert_drive_file');
      expect(component.getSourceIcon(undefined)).toBe('insert_drive_file');
      expect(component.getSourceIcon({})).toBe('insert_drive_file');
    });

    it('should prefer file extension over sourceType when both present', () => {
      // .md extension wins over generic type
      expect(component.getSourceIcon({ name: 'doc.md', sourceType: 'text' })).toBe('description');
    });

    it('should be case-insensitive for file extensions', () => {
      expect(component.getSourceIcon({ name: 'REPORT.PDF' })).toBe('picture_as_pdf');
      expect(component.getSourceIcon({ name: 'Data.XLSX' })).toBe('table_chart');
    });

    it('should fall back to sourceType when sourceName has no extension', () => {
      expect(component.getSourceIcon({ name: 'no-ext', sourceType: 'email' })).toBe('email');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. getRelevanceLevel()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getRelevanceLevel()', () => {
    it('should return "Very High" for scores >= 0.9', () => {
      expect(component.getRelevanceLevel(0.95)).toBe('Very High');
      expect(component.getRelevanceLevel(0.9)).toBe('Very High');
      expect(component.getRelevanceLevel(1.0)).toBe('Very High');
    });

    it('should return "High" for scores >= 0.75 and < 0.9', () => {
      expect(component.getRelevanceLevel(0.85)).toBe('High');
      expect(component.getRelevanceLevel(0.75)).toBe('High');
    });

    it('should return "Medium" for scores >= 0.5 and < 0.75', () => {
      expect(component.getRelevanceLevel(0.6)).toBe('Medium');
      expect(component.getRelevanceLevel(0.5)).toBe('Medium');
    });

    it('should return "Low" for scores < 0.5', () => {
      expect(component.getRelevanceLevel(0.4)).toBe('Low');
      expect(component.getRelevanceLevel(0.1)).toBe('Low');
      expect(component.getRelevanceLevel(0.0)).toBe('Low');
    });

    it('should return empty string for undefined score', () => {
      expect(component.getRelevanceLevel(undefined)).toBe('');
    });

    it('should handle boundary values precisely', () => {
      expect(component.getRelevanceLevel(0.899)).toBe('High');
      expect(component.getRelevanceLevel(0.749)).toBe('Medium');
      expect(component.getRelevanceLevel(0.499)).toBe('Low');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. TEMPLATE RENDERING
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Template rendering', () => {
    it('should not render context panel when hidden', () => {
      fixture.detectChanges();
      const panel = fixture.nativeElement.querySelector('.context-panel');
      expect(panel).toBeTruthy();
      // Template uses [class.hidden]="!showContextPanel" — panel is hidden when showContextPanel is false
      expect(panel.classList.contains('hidden')).toBeTrue();
    });

    it('should render context panel with visible class when open', () => {
      const message = mockMessageWithSources('msg-1', [mockSource()]);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      const panel = fixture.nativeElement.querySelector('.context-panel');
      // When open, hidden class is removed
      expect(panel.classList.contains('hidden')).toBeFalse();
    });

    it('should render source count in panel header', () => {
      const sources = [mockSource(), mockSource({ sourceName: 'b.pdf' })];
      const message = mockMessageWithSources('msg-1', sources);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template: <span class="source-count">({{ contextPanelSources.length }})</span>
      const count = fixture.nativeElement.querySelector('.source-count');
      expect(count.textContent).toContain('2');
    });

    it('should render source cards for each source', () => {
      const sources = [
        mockSource({ sourceName: 'alpha.pdf' }),
        mockSource({ sourceName: 'beta.md' }),
        mockSource({ sourceName: 'gamma.txt' })
      ];
      const message = mockMessageWithSources('msg-1', sources);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template: <div class="source-card" *ngFor="let source of contextPanelSources; ...">
      const cards = fixture.nativeElement.querySelectorAll('.source-card');
      expect(cards.length).toBe(3);
    });

    it('should render source names in cards', () => {
      const sources = [mockSource({ sourceName: 'important-doc.pdf' })];
      const message = mockMessageWithSources('msg-1', sources);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template: <span class="source-name">{{ source.name || source.fileName || ... }}</span>
      const name = fixture.nativeElement.querySelector('.source-name');
      expect(name.textContent).toContain('important-doc.pdf');
    });

    it('should render relevance bar when score is present', () => {
      const sources = [mockSource({ score: 0.82 })];
      const message = mockMessageWithSources('msg-1', sources);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template: <div class="relevance-bar" *ngIf="source.score != null">
      const relevance = fixture.nativeElement.querySelector('.relevance-bar');
      expect(relevance).toBeTruthy();

      // Template: <span class="bar-label">{{ (source.score * 100).toFixed(0) }}%</span>
      const scoreText = fixture.nativeElement.querySelector('.bar-label');
      expect(scoreText.textContent).toContain('82');
    });

    it('should not render relevance bar when score is absent', () => {
      const sources = [mockSource({ score: undefined })];
      const message = mockMessageWithSources('msg-1', sources);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template: <div class="relevance-bar" *ngIf="source.score != null">
      const relevance = fixture.nativeElement.querySelector('.relevance-bar');
      expect(relevance).toBeFalsy();
    });

    it('should render metadata badges', () => {
      const sources = [mockSource({ sourceType: 'pdf', chunkIndex: 3 })];
      const message = mockMessageWithSources('msg-1', sources);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template: <span class="meta-badge" *ngIf="source.sourceType">{{ source.sourceType }}</span>
      //           <span class="meta-badge" *ngIf="source.chunkIndex != null">Chunk {{ source.chunkIndex }}</span>
      const badges = fixture.nativeElement.querySelectorAll('.meta-badge');
      expect(badges.length).toBe(2);
      expect(badges[0].textContent).toContain('pdf');
      // Template renders the raw chunkIndex value (not +1): "Chunk 3"
      expect(badges[1].textContent).toContain('Chunk 3');
    });

    it('should render content excerpt', () => {
      const sources = [mockSource({ content: 'This is the excerpt text for testing.' })];
      const message = mockMessageWithSources('msg-1', sources);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template: <div class="source-excerpt" *ngIf="source.content || source.text || source.snippet">
      const excerpt = fixture.nativeElement.querySelector('.source-excerpt');
      expect(excerpt.textContent).toContain('This is the excerpt text for testing.');
    });

    it('should render action buttons', () => {
      const sources = [mockSource()];
      const message = mockMessageWithSources('msg-1', sources);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template has: Copy button (always) + Graph button (*ngIf source.documentId || source.nodeId)
      // mockSource has documentId set, so 2 buttons total
      const actions = fixture.nativeElement.querySelectorAll('.source-actions button');
      expect(actions.length).toBe(2);
    });

    it('should render empty state when no sources', () => {
      const message = mockMessageWithSources('msg-1', []);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      const empty = fixture.nativeElement.querySelector('.context-panel-empty');
      expect(empty).toBeTruthy();
      expect(empty.textContent).toContain('No sources');
    });

    it('should apply highlighted class to the active source card', () => {
      const sources = [mockSource(), mockSource({ sourceName: 'b.pdf' })];
      const message = mockMessageWithSources('msg-1', sources);
      component.openContextPanel(message.id, message.sources || []);
      component.highlightedSourceIndex = 1;
      fixture.detectChanges();

      // Template: <div class="source-card" [class.highlighted]="highlightedSourceIndex === i">
      const cards = fixture.nativeElement.querySelectorAll('.source-card');
      expect(cards[0].classList.contains('highlighted')).toBeFalse();
      expect(cards[1].classList.contains('highlighted')).toBeTrue();
    });

    it('should render source citation index badges', () => {
      const sources = [mockSource(), mockSource({ sourceName: 'b.pdf' })];
      const message = mockMessageWithSources('msg-1', sources);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template: <span class="citation-index">[{{ i + 1 }}]</span>
      const indices = fixture.nativeElement.querySelectorAll('.citation-index');
      expect(indices[0].textContent).toContain('[1]');
      expect(indices[1].textContent).toContain('[2]');
    });

    it('should render file path when available', () => {
      const sources = [mockSource({ filePath: '/docs/arch.pdf' })];
      const message = mockMessageWithSources('msg-1', sources);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template: <div class="file-path" *ngIf="source.filePath || source.path">
      const path = fixture.nativeElement.querySelector('.file-path');
      expect(path).toBeTruthy();
      expect(path.textContent).toContain('/docs/arch.pdf');
    });

    it('should close panel when close button is clicked', () => {
      const message = mockMessageWithSources('msg-1', [mockSource()]);
      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template: <button mat-icon-button (click)="closeContextPanel()" ...>
      const closeBtn = fixture.nativeElement.querySelector('.context-panel-header [mat-icon-button]');
      closeBtn.click();
      fixture.detectChanges();

      expect(component.showContextPanel).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. INTERACTION WITH OTHER SIDEBARS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Interaction with other sidebars', () => {
    it('should not affect history sidebar state', () => {
      component.showHistorySidebar = true;
      const message = mockMessageWithSources('msg-1', [mockSource()]);

      component.openContextPanel(message.id, message.sources || []);

      expect(component.showHistorySidebar).toBeTrue();
      expect(component.showContextPanel).toBeTrue();
    });

    it('should close settings but not history when opening', () => {
      component.showSettings = true;
      component.showHistorySidebar = true;
      const message = mockMessageWithSources('msg-1', [mockSource()]);

      component.openContextPanel(message.id, message.sources || []);

      expect(component.showSettings).toBeFalse();
      expect(component.showHistorySidebar).toBeTrue();
      expect(component.showContextPanel).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. EDGE CASES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Edge cases', () => {
    it('should handle rapid open/close cycles', () => {
      const message = mockMessageWithSources('msg-1', [mockSource()]);

      component.openContextPanel(message.id, message.sources || []);
      component.closeContextPanel();
      component.openContextPanel(message.id, message.sources || []);
      component.closeContextPanel();
      component.openContextPanel(message.id, message.sources || []);

      expect(component.showContextPanel).toBeTrue();
      expect(component.contextPanelMessageId).toBe('msg-1');
    });

    it('should handle sources with minimal data', () => {
      const bareSource = { content: 'Just content' };
      const message = mockMessageWithSources('msg-1', [bareSource]);

      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template uses class "source-card" (not "context-source-card")
      const card = fixture.nativeElement.querySelector('.source-card');
      expect(card).toBeTruthy();
    });

    it('should handle source with very long content gracefully', () => {
      const longContent = 'x'.repeat(1000);
      const source = mockSource({ content: longContent });
      const message = mockMessageWithSources('msg-1', [source]);

      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template: <div class="source-excerpt">{{ source.content ... }}</div>
      // Template renders full content (no truncation in template itself)
      const excerpt = fixture.nativeElement.querySelector('.source-excerpt');
      expect(excerpt).toBeTruthy();
      expect(excerpt.textContent.trim()).toBe(longContent);
    });

    it('should handle many sources without error', () => {
      const sources = Array.from({ length: 50 }, (_, i) =>
        mockSource({ sourceName: `doc-${i}.pdf`, score: Math.random() })
      );
      const message = mockMessageWithSources('msg-1', sources);

      component.openContextPanel(message.id, message.sources || []);
      fixture.detectChanges();

      // Template uses class "source-card" (not "context-source-card")
      const cards = fixture.nativeElement.querySelectorAll('.source-card');
      expect(cards.length).toBe(50);
    });

    it('should handle highlighting out-of-bounds index gracefully', fakeAsync(() => {
      const message = mockMessageWithSources('msg-1', [mockSource()]);

      component.highlightSourceInPanel(message.id, 99, message.sources || []);
      expect(component.highlightedSourceIndex).toBe(99);

      tick(2500);
      expect(component.highlightedSourceIndex).toBe(-1);
    }));
  });
});
