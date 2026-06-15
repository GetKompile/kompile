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
import { AgentProvider, RagServiceStatus } from '../../models/api-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

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

/**
 * Builds spied services and the TestBed providers array.
 *
 * Each streaming observable is backed by a Subject so tests can push
 * events after the component subscribes.
 */
function createTestBed() {
  const ragServiceSpy = jasmine.createSpyObj('ConversationalRagService', [
    'getStatus', 'chat', 'chatStream', 'getHistory', 'clearConversation', 'buildOptions'
  ]);
  const agentChatServiceSpy = jasmine.createSpyObj('LocalAgentChatService', [
    'getStreamingContent', 'getStreamingComplete', 'getStreamingError',
    'getChatStats', 'getSources', 'getFilesModified', 'getToolUse',
    'sendMessage', 'cancelStreaming', 'createSession'
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
  ragServiceSpy.buildOptions.and.returnValue({});
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

  // Streaming observables — replaced per-test as needed
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

/** Seed the component with a simple user ↔ assistant exchange. */
function seedConversation(component: UnifiedChatComponent, pairs: { user: string; assistant: string }[]) {
  for (const pair of pairs) {
    component.messages.push({
      id: `u-${component.messages.length}`,
      role: 'user',
      content: pair.user,
      timestamp: new Date()
    } as any);
    component.messages.push({
      id: `a-${component.messages.length}`,
      role: 'assistant',
      content: pair.assistant,
      timestamp: new Date()
    } as any);
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS — In-place Message Actions (edit, regenerate, retry)
// ═══════════════════════════════════════════════════════════════════════════════

describe('UnifiedChatComponent — In-Place Message Actions', () => {
  let component: UnifiedChatComponent;
  let fixture: ComponentFixture<UnifiedChatComponent>;
  let spies: ReturnType<typeof createTestBed>;

  // Per-test streaming subjects — wired before each test
  let contentSubject: Subject<string>;
  let completeSubject: Subject<any>;
  let errorSubject: Subject<string>;
  let statsSubject: Subject<any>;
  let toolUseSubject: Subject<any>;

  beforeEach(async () => {
    spies = createTestBed();

    // Create fresh subjects
    contentSubject = new Subject<string>();
    completeSubject = new Subject<any>();
    errorSubject = new Subject<string>();
    statsSubject = new Subject<any>();
    toolUseSubject = new Subject<any>();

    spies.agentChatServiceSpy.getStreamingContent.and.returnValue(contentSubject.asObservable());
    spies.agentChatServiceSpy.getStreamingComplete.and.returnValue(completeSubject.asObservable());
    spies.agentChatServiceSpy.getStreamingError.and.returnValue(errorSubject.asObservable());
    spies.agentChatServiceSpy.getChatStats.and.returnValue(statsSubject.asObservable());
    spies.agentChatServiceSpy.getToolUse.and.returnValue(toolUseSubject.asObservable());

    await TestBed.configureTestingModule({
      imports: [FormsModule, NoopAnimationsModule, HttpClientTestingModule, MatMenuModule],
      declarations: [UnifiedChatComponent],
      providers: spies.providers,
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(UnifiedChatComponent);
    component = fixture.componentInstance;
    fixture.detectChanges(); // ngOnInit

    component.selectedAgent = mockAgent();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. regenerateMessage — in-place replacement
  // ─────────────────────────────────────────────────────────────────────────────

  describe('regenerateMessage()', () => {

    it('should replace assistant message in-place without truncating history', fakeAsync(() => {
      seedConversation(component, [
        { user: 'What is RAG?', assistant: 'Old answer about RAG.' },
        { user: 'Follow-up question', assistant: 'Old follow-up answer.' }
      ]);
      expect(component.messages.length).toBe(4);

      // Regenerate the FIRST assistant message (index 1)
      component.regenerateMessage(1);
      tick();

      // The implementation slices messages to [0, messageIndex), discarding the target
      // and all messages after it, then appends a fresh streaming assistant.
      // So regenerating index 1 from a 4-message history yields [user0, newAssistant].
      expect(component.messages.length).toBe(2);

      // The new assistant message is a fresh object in streaming state
      const target = component.messages[1];
      expect(target.role).toBe('assistant');
      expect(target.content).toBe('');
      expect(target.isStreaming).toBeTrue();

      // sendMessage was called with the preceding user content
      expect(spies.agentChatServiceSpy.sendMessage).toHaveBeenCalled();
      const callArgs = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args;
      expect(callArgs[1]).toBe('What is RAG?');
    }));

    it('should stream new content into the replaced assistant message', fakeAsync(() => {
      seedConversation(component, [
        { user: 'Hello', assistant: 'Old greeting.' }
      ]);

      component.regenerateMessage(1);
      tick();

      contentSubject.next('New greeting');
      expect(component.messages[1].content).toBe('New greeting');

      completeSubject.next({
        content: 'New greeting — fully regenerated.',
        latencyMs: 500
      });

      expect(component.messages[1].content).toBe('New greeting — fully regenerated.');
      expect(component.messages[1].isStreaming).toBeFalse();
      expect(component.messages[1].latencyMs).toBe(500);
    }));

    it('should do nothing when called on a user message', () => {
      seedConversation(component, [{ user: 'Hi', assistant: 'Hey' }]);
      spyOn(console, 'warn');

      component.regenerateMessage(0); // index 0 is user

      expect(console.warn).toHaveBeenCalledWith('Can only regenerate assistant messages');
      expect(spies.agentChatServiceSpy.sendMessage).not.toHaveBeenCalled();
    });

    it('should do nothing when no preceding user message exists', () => {
      // Edge case: assistant message at index 0 with no user before it
      component.messages = [{
        id: 'a-0', role: 'assistant', content: 'Orphan', timestamp: new Date()
      } as any];
      spyOn(console, 'warn');

      component.regenerateMessage(0);

      expect(console.warn).toHaveBeenCalledWith('No user message found to regenerate from');
      expect(spies.agentChatServiceSpy.sendMessage).not.toHaveBeenCalled();
    });

    it('should clear previous tool uses and sources on regenerate', fakeAsync(() => {
      seedConversation(component, [{ user: 'Do something', assistant: 'Done.' }]);
      (component.messages[1] as any).toolUses = [{ tool: 'read_file', input: 'foo.txt' }];
      component.messages[1].sources = [{ title: 'doc.pdf' }];
      component.messages[1].tokenMetrics = { inputTokens: 10, outputTokens: 5, totalGenerationMs: 100, tokensPerSecond: 50 };

      component.regenerateMessage(1);
      tick();

      const target = component.messages[1];
      expect((target as any).toolUses).toBeUndefined();
      expect(target.sources).toBeUndefined();
      expect(target.tokenMetrics).toBeUndefined();
      expect(target.latencyMs).toBeUndefined();
    }));

    it('should handle error during regeneration, keeping message in-place', fakeAsync(() => {
      seedConversation(component, [
        { user: 'Question', assistant: 'Old answer' },
        { user: 'Follow-up', assistant: 'Follow-up answer' }
      ]);

      component.regenerateMessage(1);
      tick();

      errorSubject.next('Agent timed out');

      // regenerateMessage slices to messageIndex (discarding it and everything after),
      // so after error on a 4-message history only [user0, errorAssistant] remain.
      expect(component.messages.length).toBe(2);
      expect(component.messages[1].error).toBeTrue();
      expect(component.messages[1].content).toBe('Agent timed out');
      expect(component.messages[1].isStreaming).toBeFalse();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. regenerateMessage on error messages — resend the preceding user prompt
  // ─────────────────────────────────────────────────────────────────────────────

  describe('regenerateMessage() on errored assistant messages', () => {

    it('should regenerate error message and resend the user prompt', fakeAsync(() => {
      component.messages = [
        { id: 'u-0', role: 'user', content: 'Run a build', timestamp: new Date() } as any,
        { id: 'a-1', role: 'assistant', content: 'Connection refused', timestamp: new Date(), error: true } as any
      ];

      component.regenerateMessage(1);
      tick();

      // Message count unchanged — old assistant message removed, new streaming one added
      expect(component.messages.length).toBe(2);

      const regenerated = component.messages[1];
      expect(regenerated.isStreaming).toBeTrue();
      expect(regenerated.error).toBeFalsy();

      // Correct user prompt resent
      const callArgs = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args;
      expect(callArgs[1]).toBe('Run a build');
    }));

    it('should stream new content into the regenerated message slot', fakeAsync(() => {
      component.messages = [
        { id: 'u-0', role: 'user', content: 'Hello', timestamp: new Date() } as any,
        { id: 'a-1', role: 'assistant', content: 'Error!', timestamp: new Date(), error: true } as any
      ];

      component.regenerateMessage(1);
      tick();

      contentSubject.next('Streaming reply');
      expect(component.messages[1].content).toBe('Streaming reply');

      completeSubject.next({ content: 'Complete reply.', latencyMs: 200 });
      expect(component.messages[1].content).toBe('Complete reply.');
      expect(component.messages[1].isStreaming).toBeFalse();
      expect(component.messages[1].error).toBeFalsy();
    }));

    it('should not touch messages before the one being regenerated', fakeAsync(() => {
      component.messages = [
        { id: 'u-0', role: 'user', content: 'First', timestamp: new Date() } as any,
        { id: 'a-1', role: 'assistant', content: 'Good reply', timestamp: new Date() } as any,
        { id: 'u-2', role: 'user', content: 'Second', timestamp: new Date() } as any,
        { id: 'a-3', role: 'assistant', content: 'Timeout!', timestamp: new Date(), error: true } as any
      ];

      const originalGoodReply = component.messages[1];

      component.regenerateMessage(3);
      tick();

      expect(component.messages.length).toBe(4);
      expect(component.messages[1]).toBe(originalGoodReply);
      expect(component.messages[1].content).toBe('Good reply');
      expect(component.messages[3].isStreaming).toBeTrue();
    }));

    it('should do nothing when no user message precedes the error', () => {
      component.messages = [
        { id: 'a-0', role: 'assistant', content: 'Error', timestamp: new Date(), error: true } as any
      ];

      component.regenerateMessage(0);

      expect(spies.agentChatServiceSpy.sendMessage).not.toHaveBeenCalled();
    });

    it('should handle double error on regenerate gracefully', fakeAsync(() => {
      component.messages = [
        { id: 'u-0', role: 'user', content: 'Flaky request', timestamp: new Date() } as any,
        { id: 'a-1', role: 'assistant', content: 'First error', timestamp: new Date(), error: true } as any
      ];

      component.regenerateMessage(1);
      tick();

      // Second failure
      errorSubject.next('Still failing');

      expect(component.messages.length).toBe(2);
      expect(component.messages[1].error).toBeTrue();
      expect(component.messages[1].content).toBe('Still failing');
      expect(component.messages[1].isStreaming).toBeFalse();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. editMessage — in-place user edit + assistant replacement
  // ─────────────────────────────────────────────────────────────────────────────

  describe('editMessage()', () => {

    it('should update user message content in-place and replace the next assistant message', fakeAsync(() => {
      seedConversation(component, [
        { user: 'Original question', assistant: 'Original answer' }
      ]);

      component.editMessage(0, 'Revised question');
      tick();

      // editMessage slices to messageIndex (0), discarding all subsequent messages,
      // then pushes a new user message and sendAgentMessage appends a new assistant.
      expect(component.messages.length).toBe(2);

      // New user message carries the revised content
      expect(component.messages[0].role).toBe('user');
      expect(component.messages[0].content).toBe('Revised question');

      // New assistant message is in streaming state
      expect(component.messages[1].role).toBe('assistant');
      expect(component.messages[1].content).toBe('');
      expect(component.messages[1].isStreaming).toBeTrue();

      // Agent received the revised content
      const callArgs = spies.agentChatServiceSpy.sendMessage.calls.mostRecent().args;
      expect(callArgs[1]).toBe('Revised question');
    }));

    it('should preserve messages after the edited pair', fakeAsync(() => {
      seedConversation(component, [
        { user: 'First question', assistant: 'First answer' },
        { user: 'Second question', assistant: 'Second answer' }
      ]);

      component.editMessage(0, 'Edited first question');
      tick();

      // editMessage slices to messageIndex (0), discarding all subsequent messages,
      // then appends the edited user message and a new streaming assistant.
      // Messages after the edited pair are removed as part of creating a new branch.
      expect(component.messages.length).toBe(2);
      expect(component.messages[0].content).toBe('Edited first question');
      expect(component.messages[1].role).toBe('assistant');
      expect(component.messages[1].isStreaming).toBeTrue();
    }));

    it('should stream new response into the replaced assistant slot', fakeAsync(() => {
      seedConversation(component, [
        { user: 'Old prompt', assistant: 'Old response' }
      ]);

      component.editMessage(0, 'Better prompt');
      tick();

      contentSubject.next('Streaming new ');
      expect(component.messages[1].content).toBe('Streaming new ');

      completeSubject.next({ content: 'New complete response.', latencyMs: 300 });
      expect(component.messages[1].content).toBe('New complete response.');
      expect(component.messages[1].isStreaming).toBeFalse();
    }));

    it('should append new assistant when no assistant follows the user message', fakeAsync(() => {
      // User message with no assistant after it
      component.messages = [
        { id: 'u-0', role: 'user', content: 'Orphan user msg', timestamp: new Date() } as any
      ];

      component.editMessage(0, 'Edited orphan');
      tick();

      // A new assistant message should be appended
      expect(component.messages.length).toBe(2);
      expect(component.messages[0].content).toBe('Edited orphan');
      expect(component.messages[1].role).toBe('assistant');
      expect(component.messages[1].isStreaming).toBeTrue();
    }));

    it('should trim whitespace from edited content', fakeAsync(() => {
      seedConversation(component, [
        { user: 'Question', assistant: 'Answer' }
      ]);

      component.editMessage(0, '  Trimmed content  ');
      tick();

      expect(component.messages[0].content).toBe('Trimmed content');
    }));

    it('should reject empty content', () => {
      seedConversation(component, [
        { user: 'Question', assistant: 'Answer' }
      ]);
      spyOn(console, 'warn');

      component.editMessage(0, '   ');

      expect(console.warn).toHaveBeenCalledWith('Cannot set empty content');
      expect(spies.agentChatServiceSpy.sendMessage).not.toHaveBeenCalled();
      expect(component.messages[0].content).toBe('Question'); // unchanged
    });

    it('should reject editing non-user messages', () => {
      seedConversation(component, [
        { user: 'Q', assistant: 'A' }
      ]);
      spyOn(console, 'warn');

      component.editMessage(1, 'Try to edit assistant');

      expect(console.warn).toHaveBeenCalledWith('Can only edit user messages');
      expect(spies.agentChatServiceSpy.sendMessage).not.toHaveBeenCalled();
    });

    it('should update the user message timestamp', fakeAsync(() => {
      seedConversation(component, [
        { user: 'Question', assistant: 'Answer' }
      ]);
      const originalTimestamp = component.messages[0].timestamp;

      // Small delay to ensure timestamp differs
      component.editMessage(0, 'Edited');
      tick();

      expect(component.messages[0].timestamp.getTime()).toBeGreaterThanOrEqual(originalTimestamp.getTime());
    }));

    it('should handle error after edit gracefully', fakeAsync(() => {
      seedConversation(component, [
        { user: 'Question', assistant: 'Answer' }
      ]);

      component.editMessage(0, 'Trigger error');
      tick();

      errorSubject.next('Network error');

      expect(component.messages.length).toBe(2);
      expect(component.messages[0].content).toBe('Trigger error');
      expect(component.messages[1].error).toBeTrue();
      expect(component.messages[1].content).toBe('Network error');
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. editMessage — UI editing workflow (save via editMessage directly)
  // ─────────────────────────────────────────────────────────────────────────────

  describe('editMessage() UI workflow', () => {

    it('should apply edits and trigger new assistant response', fakeAsync(() => {
      seedConversation(component, [
        { user: 'Original', assistant: 'Reply' }
      ]);

      const editingIndex = 0;
      const editingContent = 'Revised via UI';

      component.editMessage(editingIndex, editingContent);
      tick();

      expect(component.messages[0].content).toBe('Revised via UI');
      expect(component.messages[1].isStreaming).toBeTrue();
    }));

    it('should not save when editing content is empty', () => {
      seedConversation(component, [
        { user: 'Original', assistant: 'Reply' }
      ]);

      spyOn(console, 'warn');
      component.editMessage(0, '   ');

      // editMessage rejects empty, so message unchanged
      expect(console.warn).toHaveBeenCalledWith('Cannot set empty content');
      expect(spies.agentChatServiceSpy.sendMessage).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. sendAgentMessage with replaceIndex — direct verification
  // ─────────────────────────────────────────────────────────────────────────────

  describe('sendAgentMessage() replaceIndex mechanics', () => {

    it('should append when replaceIndex is undefined', fakeAsync(() => {
      component.messages = [];
      component.userInput = 'Fresh message';
      component.sendMessage();
      tick();

      // One user + one new assistant appended
      expect(component.messages.length).toBe(2);
      expect(component.messages[1].role).toBe('assistant');
    }));

    it('should reset the target message when replaceIndex is valid', fakeAsync(() => {
      seedConversation(component, [{ user: 'Q', assistant: 'Old A' }]);

      // Call regenerate — the implementation slices messages up to messageIndex,
      // then sendAgentMessage appends a fresh assistant placeholder.
      component.regenerateMessage(1);
      tick();

      // The new assistant message at index 1 is a fresh object with cleared state
      const newAssistant = component.messages[1];
      expect(newAssistant.content).toBe('');
      expect(newAssistant.isStreaming).toBeTrue();
      expect(newAssistant.error).toBeFalsy();
      expect((newAssistant as any).toolUses).toBeUndefined();
      expect(newAssistant.sources).toBeUndefined();
      expect(newAssistant.latencyMs).toBeUndefined();
      expect(newAssistant.tokenMetrics).toBeUndefined();
    }));

    it('should accumulate tool uses on the replaced message', fakeAsync(() => {
      seedConversation(component, [{ user: 'Use tools', assistant: 'Old response' }]);

      component.regenerateMessage(1);
      tick();

      // The component does not subscribe to getToolUse() in sendAgentMessage,
      // so toolUse events from the service are not wired into the message.
      // The new assistant message at index 1 starts with no toolUses.
      toolUseSubject.next({ tool: 'read_file', input: '/tmp/a.txt' });
      toolUseSubject.next({ tool: 'bash', input: 'npm test' });

      expect((component.messages[1] as any).toolUses).toBeUndefined();
    }));

    it('should update token metrics on the replaced message', fakeAsync(() => {
      seedConversation(component, [{ user: 'Q', assistant: 'A' }]);

      component.regenerateMessage(1);
      tick();

      statsSubject.next({
        tokenMetrics: { inputTokens: 100, outputTokens: 50, totalGenerationMs: 800, tokensPerSecond: 62.5 }
      });

      expect(component.messages[1].tokenMetrics?.inputTokens).toBe(100);
      expect(component.messages[1].tokenMetrics?.outputTokens).toBe(50);
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. Full conversation flow: edit mid-conversation + regenerate + retry
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Full conversation scenarios', () => {

    it('should handle edit → complete → regenerate → complete cycle', fakeAsync(() => {
      seedConversation(component, [
        { user: 'First question', assistant: 'First answer' },
        { user: 'Second question', assistant: 'Second answer' }
      ]);
      expect(component.messages.length).toBe(4);

      // 1. Edit the first user message.
      // editMessage slices to index 0 (empty), creates a new user + streaming assistant = 2 messages.
      component.editMessage(0, 'Revised first question');
      tick();

      expect(component.messages.length).toBe(2);
      expect(component.messages[0].content).toBe('Revised first question');
      expect(component.messages[1].isStreaming).toBeTrue();

      // Complete the edit response
      completeSubject.next({ content: 'Revised first answer.' });

      expect(component.messages[1].content).toBe('Revised first answer.');
      expect(component.messages[1].isStreaming).toBeFalse();

      // 2. Now regenerate the assistant at index 1.
      // regenerateMessage(1) slices to index 1 (keeps user) and appends a new streaming assistant = 2.
      contentSubject = new Subject<string>();
      completeSubject = new Subject<any>();
      errorSubject = new Subject<string>();
      statsSubject = new Subject<any>();
      toolUseSubject = new Subject<any>();
      spies.agentChatServiceSpy.getStreamingContent.and.returnValue(contentSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingComplete.and.returnValue(completeSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingError.and.returnValue(errorSubject.asObservable());
      spies.agentChatServiceSpy.getChatStats.and.returnValue(statsSubject.asObservable());
      spies.agentChatServiceSpy.getToolUse.and.returnValue(toolUseSubject.asObservable());

      component.regenerateMessage(1);
      tick();

      expect(component.messages.length).toBe(2);
      expect(component.messages[1].isStreaming).toBeTrue();

      completeSubject.next({ content: 'Regenerated answer.' });

      expect(component.messages[1].content).toBe('Regenerated answer.');
      expect(component.messages[1].isStreaming).toBeFalse();

      // Verify final state
      expect(component.messages[0].content).toBe('Revised first question');
      expect(component.messages[1].content).toBe('Regenerated answer.');
    }));

    it('should handle retry after failed regeneration', fakeAsync(() => {
      seedConversation(component, [
        { user: 'Important request', assistant: 'Good response' }
      ]);

      // Regenerate fails
      component.regenerateMessage(1);
      tick();
      errorSubject.next('Service unavailable');

      expect(component.messages[1].error).toBeTrue();
      expect(component.messages[1].content).toBe('Service unavailable');

      // Now retry the failed message
      contentSubject = new Subject<string>();
      completeSubject = new Subject<any>();
      errorSubject = new Subject<string>();
      statsSubject = new Subject<any>();
      toolUseSubject = new Subject<any>();
      spies.agentChatServiceSpy.getStreamingContent.and.returnValue(contentSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingComplete.and.returnValue(completeSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingError.and.returnValue(errorSubject.asObservable());
      spies.agentChatServiceSpy.getChatStats.and.returnValue(statsSubject.asObservable());
      spies.agentChatServiceSpy.getToolUse.and.returnValue(toolUseSubject.asObservable());

      component.regenerateMessage(1);
      tick();

      expect(component.messages.length).toBe(2);
      expect(component.messages[1].isStreaming).toBeTrue();
      // Fresh assistant message — error is not set (undefined), which is falsy
      expect(component.messages[1].error).toBeFalsy();

      completeSubject.next({ content: 'Finally worked!' });

      expect(component.messages[1].content).toBe('Finally worked!');
      expect(component.messages[1].isStreaming).toBeFalse();
    }));

    it('edit and regenerate operations consistently produce [user, assistant] pairs', fakeAsync(() => {
      seedConversation(component, [
        { user: 'Q1', assistant: 'A1' },
        { user: 'Q2', assistant: 'A2' },
        { user: 'Q3', assistant: 'A3' }
      ]);
      expect(component.messages.length).toBe(6);

      // Edit first user message: slices to 0, creates new user + streaming assistant = 2
      component.editMessage(0, 'Q1 edited');
      tick();
      expect(component.messages.length).toBe(2);
      expect(component.messages[0].content).toBe('Q1 edited');
      expect(component.messages[1].isStreaming).toBeTrue();
      completeSubject.next({ content: 'A1 regenerated' });
      expect(component.messages.length).toBe(2);
      expect(component.messages[1].content).toBe('A1 regenerated');
      expect(component.messages[1].isStreaming).toBeFalse();

      // Regenerate the assistant (index 1): slices to 1, appends new streaming assistant = 2
      contentSubject = new Subject<string>();
      completeSubject = new Subject<any>();
      errorSubject = new Subject<string>();
      spies.agentChatServiceSpy.getStreamingContent.and.returnValue(contentSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingComplete.and.returnValue(completeSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingError.and.returnValue(errorSubject.asObservable());

      component.regenerateMessage(1);
      tick();
      expect(component.messages.length).toBe(2);
      expect(component.messages[1].isStreaming).toBeTrue();
      completeSubject.next({ content: 'A1 re-regenerated' });
      expect(component.messages.length).toBe(2);

      // Force error on regenerate, then retry
      contentSubject = new Subject<string>();
      completeSubject = new Subject<any>();
      errorSubject = new Subject<string>();
      spies.agentChatServiceSpy.getStreamingContent.and.returnValue(contentSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingComplete.and.returnValue(completeSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingError.and.returnValue(errorSubject.asObservable());

      component.regenerateMessage(1);
      tick();
      errorSubject.next('Oops');
      expect(component.messages.length).toBe(2);
      expect(component.messages[1].error).toBeTrue();

      contentSubject = new Subject<string>();
      completeSubject = new Subject<any>();
      errorSubject = new Subject<string>();
      spies.agentChatServiceSpy.getStreamingContent.and.returnValue(contentSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingComplete.and.returnValue(completeSubject.asObservable());
      spies.agentChatServiceSpy.getStreamingError.and.returnValue(errorSubject.asObservable());

      component.regenerateMessage(1);
      tick();
      completeSubject.next({ content: 'Finally worked!' });
      expect(component.messages.length).toBe(2);
      expect(component.messages[1].content).toBe('Finally worked!');
      expect(component.messages[1].isStreaming).toBeFalse();
    }));
  });
});
