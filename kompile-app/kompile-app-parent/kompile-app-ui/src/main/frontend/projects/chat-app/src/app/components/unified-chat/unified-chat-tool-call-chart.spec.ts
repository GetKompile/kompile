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
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { of, Subject } from 'rxjs';

import { UnifiedChatComponent } from './unified-chat.component';
import { ToolCallChartComponent } from '@shared/components/tool-call-chart/tool-call-chart.component';
import { ConversationalRagService } from '@shared/services/conversational-rag.service';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { AgentService } from '@shared/services/agent.service';
import { ChatStorageService } from '@shared/services/chat-storage.service';
import { ChatHistoryService } from '@shared/services/chat-history.service';
import { CliTranscriptService } from '@shared/services/cli-transcript.service';
import { FolderService } from '@shared/services/folder.service';
import { ModelContextService } from '@shared/services/model-context.service';
import { WebSocketService } from '@shared/services/websocket.service';
import { ToolCallChart, ToolUseEvent } from '@shared/models/api-models';

/** The chart an `insights` answer carries reaches the tool card it belongs to. */
describe('UnifiedChatComponent - tool call charts', () => {
  let component: UnifiedChatComponent;
  let fixture: ComponentFixture<UnifiedChatComponent>;

  const runs: ToolCallChart = {
    v: 1, kind: 'bar', title: 'Test runs', unit: 'runs', labels: ['mon', 'tue', 'wed'],
    series: [{ name: 'passing', values: [3, 4, null] }, { name: 'failing', values: [1, 0, 2] }]
  };
  const alice: ToolCallChart = {
    v: 1, kind: 'graph', title: 'Around Alice in People', factSheet: { id: '7', name: 'People' }, focus: 'a',
    nodes: [{ id: 'a', label: 'Alice', type: 'Person' }, { id: 'b', label: 'Acme', type: 'Org' }],
    edges: [{ source: 'a', target: 'b', label: 'worksFor', directed: true }],
    omitted: 0,
    link: '#/graph?factSheetId=7&focusNode=a'
  };

  beforeEach(async () => {
    const agentChat = jasmine.createSpyObj('LocalAgentChatService', [
      'getStreamingContent', 'getStreamingComplete', 'getStreamingError', 'getChatStats', 'getSources',
      'getFilesModified', 'sendMessage', 'cancelStreaming', 'createSession', 'getToolUse', 'getCompaction',
      'getContextBudget'
    ]);
    for (const stream of ['getStreamingContent', 'getStreamingComplete', 'getStreamingError', 'getChatStats',
      'getSources', 'getFilesModified', 'getToolUse', 'getCompaction', 'getContextBudget']) {
      agentChat[stream].and.returnValue(new Subject<any>().asObservable());
    }
    const rag = jasmine.createSpyObj('ConversationalRagService', ['getStatus', 'buildOptions']);
    rag.getStatus.and.returnValue(of({ available: false }));
    rag.buildOptions.and.returnValue({});
    const agents = jasmine.createSpyObj('AgentService', [
      'getAllAgents', 'getAvailableAgents', 'getChatHarnessAgents', 'refreshChatHarnessAgents', 'getKompileLocalStatus'
    ], { agents$: new Subject<any[]>().asObservable() });
    for (const list of ['getAllAgents', 'getAvailableAgents', 'getChatHarnessAgents', 'refreshChatHarnessAgents']) {
      agents[list].and.returnValue(of([]));
    }
    agents.getKompileLocalStatus.and.returnValue(of({ connected: false, modelLoaded: false, stagingUrl: null }));
    const storage = jasmine.createSpyObj('ChatStorageService', ['getSessions', 'saveSession', 'deleteSession', 'getSession']);
    storage.getSessions.and.returnValue([]);
    const history = jasmine.createSpyObj('ChatHistoryService', [
      'getSessions', 'createSession', 'getSession', 'addMessage', 'getSessionMessages', 'deleteSession',
      'updateSessionTitle', 'getMessageContent'
    ]);
    history.getSessions.and.returnValue(of([]));
    const transcripts = jasmine.createSpyObj('CliTranscriptService', [
      'listSessions', 'discoverSources', 'getTranscript', 'getSyncStatus'
    ]);
    transcripts.listSessions.and.returnValue(of([]));
    transcripts.discoverSources.and.returnValue(of({}));
    transcripts.getSyncStatus.and.returnValue(of({ running: false }));
    const folders = jasmine.createSpyObj('FolderService', ['getFolders', 'getFolderFiles'], {
      folders$: new Subject<any[]>().asObservable(),
      selectedFolder$: new Subject<any>().asObservable()
    });
    folders.getFolders.and.returnValue(of([]));
    const modelContext = jasmine.createSpyObj('ModelContextService', ['refresh', 'getStagingModelCardUrl'], {
      context$: new Subject<any>().asObservable(),
      loading$: new Subject<boolean>().asObservable(),
      backendUrl: '/api'
    });
    const webSocket = jasmine.createSpyObj('WebSocketService', ['subscribeToMonitor', 'unsubscribeFromMonitor']);
    webSocket.subscribeToMonitor.and.returnValue(new Subject<any>().asObservable());

    await TestBed.configureTestingModule({
      imports: [FormsModule, NoopAnimationsModule, HttpClientTestingModule, MatMenuModule, ToolCallChartComponent],
      declarations: [UnifiedChatComponent],
      providers: [
        { provide: ConversationalRagService, useValue: rag },
        { provide: LocalAgentChatService, useValue: agentChat },
        { provide: AgentService, useValue: agents },
        { provide: ChatStorageService, useValue: storage },
        { provide: ChatHistoryService, useValue: history },
        { provide: CliTranscriptService, useValue: transcripts },
        { provide: FolderService, useValue: folders },
        { provide: ModelContextService, useValue: modelContext },
        { provide: WebSocketService, useValue: webSocket },
        { provide: MatDialog, useValue: jasmine.createSpyObj('MatDialog', ['open']) }
      ],
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(UnifiedChatComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  /** Shows one finished answer whose only tool call is an `insights` call carrying `chart`. */
  function answerWith(chart?: ToolCallChart): HTMLElement {
    const call: ToolUseEvent = {
      tool: 'insights', input: '{"question":"test runs this week"}', callId: 'c1', status: 'completed', ok: true,
      textOffset: 0,
      detail: {
        displayName: 'Insights', title: 'test runs this week',
        sections: [{ label: 'result', runs: [{ text: 'mon  3 passing  1 failing' }] }],
        chart
      }
    };
    component.messages = [
      { id: 'u1', role: 'user', content: 'How did the tests do this week?', timestamp: new Date() },
      { id: 'a1', role: 'assistant', content: 'Wednesday had the most failures.', isStreaming: false,
        timestamp: new Date(), toolUses: [call] }
    ] as any[];
    fixture.detectChanges();
    const cards = fixture.nativeElement.querySelectorAll('[data-testid="tool-call-card"]');
    expect(cards.length).toBe(1);
    return cards[0];
  }

  it('draws the chart inside the card, above the result', () => {
    const card = answerWith(runs);
    const figure = card.querySelector('app-tool-call-chart figure')!;
    expect(figure.getAttribute('data-kind')).toBe('bar');
    expect(figure.querySelectorAll('rect').length).toBe(5);
    const section = card.querySelector('.tool-call-section')!;
    expect(figure.compareDocumentPosition(section) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('links a graph answer to the graph page at its node', () => {
    const card = answerWith(alice);
    expect(card.querySelectorAll('app-tool-call-chart .node').length).toBe(2);
    expect(card.querySelector('app-tool-call-chart a')!.getAttribute('href')).toBe('#/graph?factSheetId=7&focusNode=a');
  });

  it('leaves a call without a chart as it was', () => {
    const card = answerWith();
    expect(card.querySelector('app-tool-call-chart')).toBeNull();
    expect(card.querySelector('.tool-call-section')!.textContent).toContain('mon  3 passing  1 failing');
  });
});
