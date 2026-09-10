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
import { ConversationalRagService } from '@shared/services/conversational-rag.service';
import { LocalAgentChatService, CompactionEvent } from '@shared/services/local-agent-chat.service';
import { AgentService } from '@shared/services/agent.service';
import { ChatStorageService } from '@shared/services/chat-storage.service';
import { ChatHistoryService } from '@shared/services/chat-history.service';
import { CliTranscriptService } from '@shared/services/cli-transcript.service';
import { FolderService } from '@shared/services/folder.service';
import { ModelContextService } from '@shared/services/model-context.service';
import { WebSocketService } from '@shared/services/websocket.service';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { RagServiceStatus } from '@shared/models/api-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

function createTestBed(compactionSubject: Subject<CompactionEvent>) {
  const ragServiceSpy = jasmine.createSpyObj('ConversationalRagService', [
    'getStatus', 'chat', 'chatStream', 'getHistory', 'clearConversation', 'buildOptions'
  ]);
  const agentChatServiceSpy = jasmine.createSpyObj('LocalAgentChatService', [
    'getStreamingContent', 'getStreamingComplete', 'getStreamingError',
    'getChatStats', 'getSources', 'getFilesModified', 'sendMessage',
    'cancelStreaming', 'createSession', 'getToolUse', 'getCompaction',
    'getContextBudget', 'compactChat'
  ]);
  const agentServiceSpy = jasmine.createSpyObj('AgentService', [
    'getAllAgents', 'getAvailableAgents', 'getChatHarnessAgents',
    'refreshChatHarnessAgents', 'getKompileLocalStatus'
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
    'listSessions', 'discoverSources', 'getTranscript', 'getSyncStatus'
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
  agentServiceSpy.getChatHarnessAgents.and.returnValue(of([]));
  agentServiceSpy.refreshChatHarnessAgents.and.returnValue(of([]));
  agentServiceSpy.getKompileLocalStatus.and.returnValue(of({
    connected: false, modelLoaded: false, stagingUrl: null
  } as any));
  chatStorageServiceSpy.getSessions.and.returnValue([]);
  chatHistoryServiceSpy.getSessions.and.returnValue(of([]));
  cliTranscriptServiceSpy.listSessions.and.returnValue(of([]));
  cliTranscriptServiceSpy.discoverSources.and.returnValue(of({}));
  cliTranscriptServiceSpy.getSyncStatus.and.returnValue(of({
    running: false, sourceIndex: 0, totalSources: 0, sourcePending: 0, sourceImported: 0
  } as any));
  folderServiceSpy.getFolders.and.returnValue(of([]));

  // Streaming observables
  agentChatServiceSpy.getStreamingContent.and.returnValue(new Subject<string>().asObservable());
  agentChatServiceSpy.getStreamingComplete.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getStreamingError.and.returnValue(new Subject<string>().asObservable());
  agentChatServiceSpy.getChatStats.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getSources.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getFilesModified.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getToolUse.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getCompaction.and.returnValue(compactionSubject.asObservable());
  agentChatServiceSpy.getContextBudget.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.sendMessage.and.returnValue(Promise.resolve());

  return {
    agentChatServiceSpy,
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
// TESTS — the transcript must visibly indicate when compaction happened
// ═══════════════════════════════════════════════════════════════════════════════

describe('UnifiedChatComponent - Compaction indicator', () => {
  let component: UnifiedChatComponent;
  let fixture: ComponentFixture<UnifiedChatComponent>;
  let compactionSubject: Subject<CompactionEvent>;

  beforeEach(async () => {
    compactionSubject = new Subject<CompactionEvent>();
    const spies = createTestBed(compactionSubject);

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

  function pushCompactionMessage(): void {
    component.messages = [
      { id: 'u1', role: 'user', content: 'long question', timestamp: new Date() },
      {
        id: 'c1', role: 'system', kind: 'compaction',
        content: 'Context compacted: ~45.0k → ~8.0k tokens',
        compaction: {
          tokensBefore: 45000, tokensAfter: 8000, contextWindow: 200000,
          model: 'claude-sonnet-4', summary: 'They discussed the build pipeline.'
        },
        timestamp: new Date()
      },
      { id: 'a1', role: 'assistant', content: 'answer', timestamp: new Date() }
    ] as any[];
    fixture.detectChanges();
  }

  describe('divider banner rendering', () => {
    it('should render a compaction divider with the token math', () => {
      pushCompactionMessage();

      const divider = fixture.nativeElement.querySelector('[data-testid="compaction-divider"]');
      expect(divider).toBeTruthy();
      const text = divider.textContent;
      expect(text).toContain('Context compacted');
      expect(text).toContain('45.0k');
      expect(text).toContain('8.0k');
      expect(text).toContain('claude-sonnet-4');
      expect(text).toContain('200.0k');
    });

    it('should not render a chat bubble for the compaction message', () => {
      pushCompactionMessage();

      const wrappers = fixture.nativeElement.querySelectorAll('[data-testid="message-wrapper"]');
      expect(wrappers.length).toBe(3);
      const compactionWrapper = Array.from(wrappers as NodeListOf<HTMLElement>)
        .find(w => w.querySelector('[data-testid="compaction-divider"]'));
      expect(compactionWrapper).toBeTruthy();
      expect(compactionWrapper!.querySelector('[data-testid="message-bubble"]')).toBeNull();
    });

    it('should expose the summary sent to the model in an expandable block', () => {
      pushCompactionMessage();

      const summary = fixture.nativeElement.querySelector('.compaction-summary');
      expect(summary).toBeTruthy();
      expect(summary.textContent).toContain('View summary sent to the model');
      expect(summary.textContent).toContain('They discussed the build pipeline.');
    });

    it('should note when the deterministic digest fallback was used', () => {
      component.messages = [
        {
          id: 'c1', role: 'system', kind: 'compaction',
          content: 'Context compacted: ~45.0k → ~8.0k tokens',
          compaction: {
            tokensBefore: 45000, tokensAfter: 8000, contextWindow: 200000,
            model: 'claude-sonnet-4', summary: 'digest text', usedFallback: true
          },
          timestamp: new Date()
        }
      ] as any[];
      fixture.detectChanges();

      const note = fixture.nativeElement.querySelector('.compaction-divider-note');
      expect(note.textContent).toContain('deterministic digest');
    });
  });

  describe('server-side compaction SSE event', () => {
    it('should append a compaction divider when the backend reports it compacted', () => {
      expect(component.messages.length).toBe(0);

      compactionSubject.next({
        tokensBefore: 30000, tokensAfter: 5000, contextWindow: 32768,
        model: 'local/lfm2', summary: 'compact summary', usedFallback: false
      });
      fixture.detectChanges();

      const compactionMessages = component.messages.filter(m => (m as any).kind === 'compaction');
      expect(compactionMessages.length).toBe(1);
      expect((compactionMessages[0] as any).compaction.tokensBefore).toBe(30000);
      expect((compactionMessages[0] as any).compaction.model).toBe('local/lfm2');

      const divider = fixture.nativeElement.querySelector('[data-testid="compaction-divider"]');
      expect(divider).toBeTruthy();
      expect(divider.textContent).toContain('local/lfm2');
    });
  });

  describe('system message labels', () => {
    it('should label plain notices as System and monitor wake-ups as Monitor', () => {
      component.messages = [
        { id: 'n1', role: 'system', kind: 'notice', content: 'plain notice', timestamp: new Date() },
        { id: 'm1', role: 'system', kind: 'monitor', content: 'monitor fired', timestamp: new Date() }
      ] as any[];
      fixture.detectChanges();

      const bubbles = fixture.nativeElement.querySelectorAll('[data-testid="message-bubble"]');
      expect(bubbles.length).toBe(2);
      expect(bubbles[0].textContent).toContain('System');
      expect(bubbles[0].textContent).not.toContain('Monitor');
      expect(bubbles[1].textContent).toContain('Monitor');
    });
  });
});
