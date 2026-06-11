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
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { of, BehaviorSubject, Subject } from 'rxjs';

import { ProcessDiagramComponent } from './process-diagram.component';
import { ProcessDiagramService, DiagramSession, TranscriptEntry } from '../../services/process-diagram.service';
import { AgentService } from '../../services/agent.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { AgentProvider, FactSheet } from '../../models/api-models';

// ─── Test Data Factories ────────────────────────────────────────────────────

function makeAgent(overrides: Partial<AgentProvider> = {}): AgentProvider {
  return {
    name: 'claude',
    displayName: 'Claude',
    description: 'Claude AI agent',
    status: 'available',
    available: true,
    ...overrides
  } as AgentProvider;
}

function makeFactSheet(overrides: Partial<FactSheet> = {}): FactSheet {
  return {
    id: 1,
    name: 'Test Fact Sheet',
    description: 'Test description',
    isActive: true,
    factCount: 10,
    indexedCount: 10,
    unindexedCount: 0,
    ...overrides
  } as FactSheet;
}

function makeSession(overrides: Partial<DiagramSession> = {}): DiagramSession {
  return {
    id: 1,
    factSheetId: 1,
    prompt: 'Find purchase order workflow',
    agentName: 'claude',
    status: 'COMPLETED',
    transcriptJson: null,
    mermaidCode: 'flowchart TD\n    A[Start] --> B[End]',
    title: 'Purchase Order Workflow',
    description: 'End-to-end purchase order process',
    sourcesJson: null,
    processDefinitionId: null,
    errorMessage: null,
    createdAt: '2025-01-01T00:00:00Z',
    updatedAt: '2025-01-01T00:01:00Z',
    completedAt: '2025-01-01T00:01:00Z',
    ...overrides
  };
}

// ─────────────────────────────────────────────────────────────────────────────

describe('ProcessDiagramComponent', () => {
  let component: ProcessDiagramComponent;
  let fixture: ComponentFixture<ProcessDiagramComponent>;
  let mockDiagramService: jasmine.SpyObj<ProcessDiagramService>;
  let mockAgentService: jasmine.SpyObj<AgentService>;
  let mockFactSheetService: jasmine.SpyObj<FactSheetService>;

  // BehaviorSubjects for streaming state
  let isStreamingSubject: BehaviorSubject<boolean>;
  let transcriptSubject: BehaviorSubject<TranscriptEntry[]>;
  let sessionIdSubject: BehaviorSubject<number | null>;
  let streamErrorSubject: Subject<string>;
  let streamCompleteSubject: Subject<void>;
  let streamingContentSubject: BehaviorSubject<string>;
  let sheetsSubject: BehaviorSubject<FactSheet[]>;

  const mockAgents: AgentProvider[] = [
    makeAgent({ name: 'claude', displayName: 'Claude' }),
    makeAgent({ name: 'codex', displayName: 'Codex' })
  ];
  const mockSheets: FactSheet[] = [
    makeFactSheet({ id: 1, name: 'Active Sheet', isActive: true }),
    makeFactSheet({ id: 2, name: 'Other Sheet', isActive: false })
  ];

  beforeEach(async () => {
    isStreamingSubject = new BehaviorSubject<boolean>(false);
    transcriptSubject = new BehaviorSubject<TranscriptEntry[]>([]);
    sessionIdSubject = new BehaviorSubject<number | null>(null);
    streamErrorSubject = new Subject<string>();
    streamCompleteSubject = new Subject<void>();
    streamingContentSubject = new BehaviorSubject<string>('');
    sheetsSubject = new BehaviorSubject<FactSheet[]>(mockSheets);

    mockDiagramService = jasmine.createSpyObj('ProcessDiagramService', [
      'startGeneration',
      'cancelGeneration',
      'listSessions',
      'getSession',
      'deleteSession',
      'finalizeSession',
      'failSession',
      'convertToProcess',
      'getSessionProvenance',
      'updateTitle',
      'updateMermaid'
    ], {
      isStreaming: isStreamingSubject.asObservable(),
      transcript: transcriptSubject.asObservable(),
      currentSessionId: sessionIdSubject.asObservable(),
      streamError: streamErrorSubject.asObservable(),
      streamComplete: streamCompleteSubject.asObservable(),
      streamingContent: streamingContentSubject.asObservable()
    });
    mockDiagramService.listSessions.and.returnValue(of([]));
    mockDiagramService.failSession.and.returnValue(of(makeSession({ status: 'FAILED' })));
    mockDiagramService.deleteSession.and.returnValue(of(undefined as any));
    mockDiagramService.getSessionProvenance.and.returnValue(of([]));

    mockAgentService = jasmine.createSpyObj('AgentService', [
      'getAvailableAgents'
    ]);
    mockAgentService.getAvailableAgents.and.returnValue(of(mockAgents));

    mockFactSheetService = jasmine.createSpyObj('FactSheetService', [
      'loadSheets',
      'getSheets'
    ], {
      sheets$: sheetsSubject.asObservable()
    });
    mockFactSheetService.loadSheets.and.returnValue(of(mockSheets));

    await TestBed.configureTestingModule({
      imports: [ProcessDiagramComponent, HttpClientTestingModule, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideComponent(ProcessDiagramComponent, {
      set: {
        providers: [
          { provide: ProcessDiagramService, useValue: mockDiagramService },
          { provide: AgentService, useValue: mockAgentService },
          { provide: FactSheetService, useValue: mockFactSheetService }
        ]
      }
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProcessDiagramComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 1. Component creation
  // ─────────────────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 2. Initial state
  // ─────────────────────────────────────────────────────────────────────────

  describe('Initial state', () => {
    it('should load available agents on init', () => {
      expect(mockAgentService.getAvailableAgents).toHaveBeenCalled();
      expect(component.availableAgents.length).toBe(2);
    });

    it('should auto-select the first agent', () => {
      expect(component.selectedAgent).toBe('claude');
    });

    it('should load fact sheets on init', () => {
      expect(mockFactSheetService.loadSheets).toHaveBeenCalled();
    });

    it('should auto-select the active fact sheet', () => {
      expect(component.selectedFactSheetId).toBe(1);
    });

    it('should start with no streaming', () => {
      expect(component.isStreaming).toBeFalse();
    });

    it('should start with no mermaid code', () => {
      expect(component.currentMermaidCode).toBeNull();
    });

    it('should start with empty transcript', () => {
      expect(component.transcriptEntries.length).toBe(0);
    });

    it('should load saved sessions on init', () => {
      expect(mockDiagramService.listSessions).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 3. Form controls — agent selector
  // ─────────────────────────────────────────────────────────────────────────

  describe('Agent selector', () => {
    it('should populate agent dropdown from service', () => {
      expect(component.availableAgents[0].name).toBe('claude');
      expect(component.availableAgents[1].name).toBe('codex');
    });

    it('should handle empty agent list gracefully', () => {
      mockAgentService.getAvailableAgents.and.returnValue(of([]));
      component.availableAgents = [];
      component.selectedAgent = '';
      // startGeneration should be guarded — no agent selected
      component.promptText = 'test prompt';
      component.startGeneration();
      expect(mockDiagramService.startGeneration).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 4. Form controls — fact sheet selector
  // ─────────────────────────────────────────────────────────────────────────

  describe('Fact sheet selector', () => {
    it('should populate fact sheet dropdown from service', () => {
      expect(component.factSheets.length).toBe(2);
      expect(component.factSheets[0].name).toBe('Active Sheet');
    });

    it('should allow null fact sheet (all fact sheets)', () => {
      component.selectedFactSheetId = null;
      expect(component.selectedFactSheetId).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 5. Generation — start
  // ─────────────────────────────────────────────────────────────────────────

  describe('Start generation', () => {
    it('should pass prompt, agent, and fact sheet to service', () => {
      component.promptText = 'Find the invoice approval workflow';
      component.selectedAgent = 'claude';
      component.selectedFactSheetId = 42;

      component.startGeneration();

      expect(mockDiagramService.startGeneration).toHaveBeenCalledWith({
        prompt: 'Find the invoice approval workflow',
        agentName: 'claude',
        factSheetId: 42
      });
    });

    it('should trim the prompt before sending', () => {
      component.promptText = '  leading and trailing spaces  ';
      component.selectedAgent = 'claude';
      component.selectedFactSheetId = 1;

      component.startGeneration();

      expect(mockDiagramService.startGeneration).toHaveBeenCalledWith(
        jasmine.objectContaining({ prompt: 'leading and trailing spaces' })
      );
    });

    it('should not start if prompt is empty', () => {
      component.promptText = '   ';
      component.selectedAgent = 'claude';
      component.startGeneration();
      expect(mockDiagramService.startGeneration).not.toHaveBeenCalled();
    });

    it('should not start if no agent selected', () => {
      component.promptText = 'Find processes';
      component.selectedAgent = '';
      component.startGeneration();
      expect(mockDiagramService.startGeneration).not.toHaveBeenCalled();
    });

    it('should reset diagram state before starting', () => {
      component.currentMermaidCode = 'old code';
      component.currentTitle = 'old title';
      component.currentDescription = 'old desc';
      component.currentSources = 'old sources';
      component.currentProcessDefinitionId = 'old-id';
      component.promptText = 'New search';
      component.selectedAgent = 'claude';

      component.startGeneration();

      expect(component.currentMermaidCode).toBeNull();
      expect(component.currentTitle).toBeNull();
      expect(component.currentDescription).toBeNull();
      expect(component.currentSources).toBeNull();
      expect(component.currentProcessDefinitionId).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 6. Generation — cancel
  // ─────────────────────────────────────────────────────────────────────────

  describe('Cancel generation', () => {
    it('should call service cancelGeneration', () => {
      component.cancelGeneration();
      expect(mockDiagramService.cancelGeneration).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 7. Streaming state
  // ─────────────────────────────────────────────────────────────────────────

  describe('Streaming state', () => {
    it('should reflect streaming state from service', () => {
      isStreamingSubject.next(true);
      fixture.detectChanges();
      expect(component.isStreaming).toBeTrue();

      isStreamingSubject.next(false);
      fixture.detectChanges();
      expect(component.isStreaming).toBeFalse();
    });

    it('should update transcript entries from service', () => {
      const entries: TranscriptEntry[] = [
        { timestamp: new Date().toISOString(), type: 'chunk', content: 'Hello' },
        { timestamp: new Date().toISOString(), type: 'tool_use', content: 'graph_get_overview' }
      ];
      transcriptSubject.next(entries);
      fixture.detectChanges();
      expect(component.transcriptEntries.length).toBe(2);
      expect(component.transcriptEntries[0].type).toBe('chunk');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 8. Session management
  // ─────────────────────────────────────────────────────────────────────────

  describe('Session management', () => {
    it('should load a saved session into the UI', () => {
      const session = makeSession({
        mermaidCode: 'flowchart TD\n    A --> B',
        title: 'PO Approval',
        description: 'Purchase order approval flow',
        processDefinitionId: 'proc-123'
      });

      component.loadSession(session);

      expect(component.currentMermaidCode).toBe('flowchart TD\n    A --> B');
      expect(component.currentTitle).toBe('PO Approval');
      expect(component.currentDescription).toBe('Purchase order approval flow');
      expect(component.currentProcessDefinitionId).toBe('proc-123');
    });

    it('should parse transcript JSON when loading session', () => {
      const entries = [
        { timestamp: '2025-01-01T00:00:00Z', type: 'chunk', content: 'test' }
      ];
      const session = makeSession({ transcriptJson: JSON.stringify(entries) });

      component.loadSession(session);

      expect(component.transcriptEntries.length).toBe(1);
      expect(component.transcriptEntries[0].content).toBe('test');
    });

    it('should handle invalid transcript JSON gracefully', () => {
      const session = makeSession({ transcriptJson: 'not-json' });

      component.loadSession(session);

      expect(component.transcriptEntries.length).toBe(0);
    });

    it('should delete a session', () => {
      const session = makeSession({ id: 5 });
      component.savedSessions = [session, makeSession({ id: 6 })];

      component.deleteSession(session);

      expect(mockDiagramService.deleteSession).toHaveBeenCalledWith(5);
    });

    it('should refresh sessions list', () => {
      mockDiagramService.listSessions.calls.reset();
      component.loadSessions();
      expect(mockDiagramService.listSessions).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 9. Convert to process
  // ─────────────────────────────────────────────────────────────────────────

  describe('Convert to process', () => {
    it('should not convert if no session or mermaid code', () => {
      component.currentMermaidCode = null;
      component.convertToProcess();
      expect(mockDiagramService.convertToProcess).not.toHaveBeenCalled();
    });

    it('should call service convertToProcess with session ID', () => {
      (component as any).currentSessionId = 42;
      component.currentMermaidCode = 'flowchart TD\n    A --> B';
      mockDiagramService.convertToProcess.and.returnValue(of({
        id: 'proc-new',
        name: 'Test Process',
        phases: [{ id: 'p1', name: 'Main', order: 1, steps: [] }]
      }));

      component.convertToProcess();

      expect(mockDiagramService.convertToProcess).toHaveBeenCalledWith(42);
    });

    it('should set processDefinitionId after successful conversion', () => {
      (component as any).currentSessionId = 42;
      component.currentMermaidCode = 'flowchart TD\n    A --> B';
      mockDiagramService.convertToProcess.and.returnValue(of({
        id: 'proc-new',
        name: 'Test Process',
        phases: []
      }));

      component.convertToProcess();

      expect(component.currentProcessDefinitionId).toBe('proc-new');
      expect(component.isConverting).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 10. UI helpers
  // ─────────────────────────────────────────────────────────────────────────

  describe('UI helpers', () => {
    it('should return correct icons for entry types', () => {
      expect(component.getEntryIcon('chunk')).toBeTruthy();
      expect(component.getEntryIcon('tool_use')).toBeTruthy();
      expect(component.getEntryIcon('error')).toBeTruthy();
    });

    it('should truncate long content', () => {
      const longContent = 'x'.repeat(500);
      const truncated = component.truncateContent(longContent, 100);
      expect(truncated.length).toBeLessThanOrEqual(103); // 100 + '...'
    });

    it('should not truncate short content', () => {
      expect(component.truncateContent('short', 100)).toBe('short');
    });

    it('should return status icons for session statuses', () => {
      expect(component.getStatusIcon('COMPLETED')).toBeTruthy();
      expect(component.getStatusIcon('FAILED')).toBeTruthy();
      expect(component.getStatusIcon('RUNNING')).toBeTruthy();
    });
  });
});
