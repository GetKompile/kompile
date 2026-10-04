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
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { of, Subject } from 'rxjs';
import { LocalAgentChatService, SessionInsightsSnapshot } from '@shared/services/local-agent-chat.service';
import { ChatStorageService } from '@shared/services/chat-storage.service';
import { ConversationalRagService } from '@shared/services/conversational-rag.service';
import { AgentService } from '@shared/services/agent.service';
import { ChatHistoryService } from '@shared/services/chat-history.service';
import { CliTranscriptService } from '@shared/services/cli-transcript.service';
import { FolderService } from '@shared/services/folder.service';
import { ModelContextService } from '@shared/services/model-context.service';
import { WebSocketService } from '@shared/services/websocket.service';
import { AgentProvider } from '@shared/models/api-models';
import {
  INSIGHTS_DRAWER_OPEN_KEY, SessionInsightsDrawerComponent
} from '../session-insights-drawer/session-insights-drawer.component';
import { UnifiedChatComponent } from './unified-chat.component';

/** Fixture-mounted harness (mirrors unified-chat-workflow-team.spec.ts) plus the drawer's reads. */
function createInsightsTestBed() {
  const agentChatServiceSpy = jasmine.createSpyObj('LocalAgentChatService', [
    'getStreamingContent', 'getStreamingComplete', 'getStreamingError',
    'getChatStats', 'getSources', 'getFilesModified', 'sendMessage',
    'cancelStreaming', 'createSession', 'getToolUse', 'getCompaction', 'getContextBudget',
    'getCommandOutcomes', 'getWorkflowTeam', 'approveWorkflowGate',
    'getToolCalls', 'getSessionInsights'
  ]);
  const agentServiceSpy = jasmine.createSpyObj('AgentService', [
    'getAllAgents', 'getAvailableAgents', 'getChatHarnessAgents',
    'refreshChatHarnessAgents', 'getKompileLocalStatus'
  ], { agents$: new Subject<AgentProvider[]>().asObservable() });
  const chatStorageServiceSpy = jasmine.createSpyObj('ChatStorageService', [
    'getSessions', 'saveSession', 'deleteSession', 'getSession'
  ]);
  const chatHistoryServiceSpy = jasmine.createSpyObj('ChatHistoryService', [
    'getSessions', 'createSession', 'getSession', 'addMessage',
    'getSessionMessages', 'deleteSession', 'updateSessionTitle', 'getMessageContent'
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
  const ragServiceSpy = jasmine.createSpyObj('ConversationalRagService', ['getStatus']);
  ragServiceSpy.getStatus.and.returnValue(of({ available: false, service: '' }));
  const dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);

  agentChatServiceSpy.getStreamingContent.and.returnValue(new Subject<string>().asObservable());
  agentChatServiceSpy.getStreamingComplete.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getStreamingError.and.returnValue(new Subject<string>().asObservable());
  agentChatServiceSpy.getChatStats.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getSources.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getFilesModified.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getToolUse.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getCompaction.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getToolCalls.and.returnValue(new Subject<any>().asObservable());
  agentChatServiceSpy.getWorkflowTeam.and.returnValue(null);
  agentChatServiceSpy.sendMessage.and.returnValue(Promise.resolve());
  agentChatServiceSpy.createSession.and.returnValue({
    id: 'agent-session-1', name: 'Test Session', messages: [],
    createdAt: new Date().toISOString()
  });
  agentServiceSpy.getAllAgents.and.returnValue(of([]));
  agentServiceSpy.getAvailableAgents.and.returnValue(of([]));
  agentServiceSpy.getChatHarnessAgents.and.returnValue(of([]));
  agentChatServiceSpy.getContextBudget.and.returnValue(of(null));
  agentServiceSpy.refreshChatHarnessAgents.and.returnValue(of([]));
  chatStorageServiceSpy.getSessions.and.returnValue([]);
  chatHistoryServiceSpy.getSessions.and.returnValue(of([]));
  cliTranscriptServiceSpy.listSessions.and.returnValue(of([]));
  cliTranscriptServiceSpy.discoverSources.and.returnValue(of({}));
  cliTranscriptServiceSpy.getSyncStatus.and.returnValue(of({
    running: false, sourceIndex: 0, totalSources: 0, sourcePending: 0, sourceImported: 0
  } as any));
  folderServiceSpy.getFolders.and.returnValue(of([]));

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

/* The real drawer is mounted, so these check the bindings reach its inputs, not just the element. */
describe('UnifiedChat session insights drawer', () => {
  let fixture: ComponentFixture<UnifiedChatComponent>;
  let component: UnifiedChatComponent;
  let service: jasmine.SpyObj<LocalAgentChatService>;
  /** One pending answer per read, in the order the drawer asked for them. */
  let answers: Subject<SessionInsightsSnapshot>[];
  let savedSessions: string | null;

  beforeEach(async () => {
    savedSessions = localStorage.getItem('unified_chat_sessions');
    localStorage.removeItem('unified_chat_sessions');
    // The drawer opens as it was left; these tests leave it open.
    localStorage.setItem(INSIGHTS_DRAWER_OPEN_KEY, 'true');
    const bed = createInsightsTestBed();
    service = bed.agentChatServiceSpy;
    answers = [];
    service.getSessionInsights.and.callFake(() => {
      const answer = new Subject<SessionInsightsSnapshot>();
      answers.push(answer);
      return answer.asObservable();
    });
    await TestBed.configureTestingModule({
      imports: [FormsModule, NoopAnimationsModule, HttpClientTestingModule, MatMenuModule,
        SessionInsightsDrawerComponent],
      declarations: [UnifiedChatComponent],
      providers: bed.providers,
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();
    fixture = TestBed.createComponent(UnifiedChatComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('workingDirectory', '/work');
  });

  afterEach(() => {
    fixture.destroy(); // the drawer's poll timer goes with it
    localStorage.removeItem(INSIGHTS_DRAWER_OPEN_KEY);
    if (savedSessions === null) localStorage.removeItem('unified_chat_sessions');
    else localStorage.setItem('unified_chat_sessions', savedSessions);
  });

  const text = (element: Element | null | undefined): string => (element?.textContent || '').replace(/\s+/g, ' ').trim();
  const drawer = (): HTMLElement | null => fixture.nativeElement.querySelector('[data-testid="session-insights"]');

  function answerLatest(sessionId: string, lines: string[]): void {
    const answer = answers[answers.length - 1];
    answer.next({ menu: 'insights', available: true, sessionId, lines });
    answer.complete();
  }

  it('reads the session the harness keys this chat by, in the chat\'s working directory', () => {
    fixture.detectChanges();
    expect(service.getSessionInsights).not.toHaveBeenCalled();
    expect(text(drawer())).toContain('Insights appear once this chat has a session.');

    component.newChat();
    const sessionId = component.currentSession!.id;
    expect(component.insightsSessionId).toBe(sessionId);
    expect(service.getSessionInsights.calls.allArgs()).toEqual([[sessionId, '/work']]);

    answerLatest(sessionId, ['Judge: 1 flag', '↳ bash: blocked rm -rf build']);
    expect(text(drawer())).toContain('Judge: 1 flag');
    expect(text(drawer())).toContain('bash: blocked rm -rf build');
  });

  it('follows the chat to its next session and drops the last one\'s rows', () => {
    fixture.detectChanges();
    component.newChat();
    const first = component.currentSession!.id;
    answerLatest(first, ['Tools: 3 calls']);
    expect(text(drawer())).toContain('Tools: 3 calls');

    component.newChat();
    const second = component.currentSession!.id;
    expect(second).not.toBe(first);
    expect(service.getSessionInsights.calls.mostRecent().args).toEqual([second, '/work']);
    expect(text(drawer())).not.toContain('Tools: 3 calls');
  });

  it('waits while the chat view is inactive and reads once it is shown', () => {
    fixture.componentRef.setInput('viewActive', false);
    fixture.detectChanges();
    component.newChat();
    expect(service.getSessionInsights).not.toHaveBeenCalled();

    fixture.componentRef.setInput('viewActive', true);
    fixture.detectChanges();
    expect(service.getSessionInsights.calls.allArgs()).toEqual([[component.currentSession!.id, '/work']]);
  });
});
