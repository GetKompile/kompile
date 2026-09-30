import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { of, Subject } from 'rxjs';
import { LocalAgentChatService, WorkflowApprovalOutcome, WorkflowTeam } from '@shared/services/local-agent-chat.service';
import { ChatStorageService } from '@shared/services/chat-storage.service';
import { ConversationalRagService } from '@shared/services/conversational-rag.service';
import { AgentService } from '@shared/services/agent.service';
import { ChatHistoryService } from '@shared/services/chat-history.service';
import { CliTranscriptService } from '@shared/services/cli-transcript.service';
import { FolderService } from '@shared/services/folder.service';
import { ModelContextService } from '@shared/services/model-context.service';
import { WebSocketService } from '@shared/services/websocket.service';
import { AgentProvider } from '@shared/models/api-models';
import { UnifiedChatComponent } from './unified-chat.component';

/** Fixture-mounted harness (mirrors unified-chat-commands.spec.ts) with the workflow team calls stubbed. */
function createWorkflowTestBed() {
  const agentChatServiceSpy = jasmine.createSpyObj('LocalAgentChatService', [
    'getStreamingContent', 'getStreamingComplete', 'getStreamingError',
    'getChatStats', 'getSources', 'getFilesModified', 'sendMessage',
    'cancelStreaming', 'createSession', 'getToolUse', 'getCompaction', 'getContextBudget',
    'getCommandOutcomes', 'getWorkflowTeam', 'approveWorkflowGate'
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

// The team as the harness session event reports it: design approved, ship awaiting.
const team: WorkflowTeam = {
  name: 'review', version: 2, lead: 'lead',
  participants: [
    { id: 'lead', role: 'coordinator', model: 'custom/lead-model', capabilities: ['plan', 'review'], delegatesTo: ['worker'] },
    { id: 'worker', role: 'implementer', model: 'custom/worker-model', delegatesTo: [] }
  ],
  routing: { implement: 'worker' },
  gates: { implementationRequires: 'design', completionRequires: 'ship', approved: ['design'] },
  maxConcurrentWorkers: 1
};

describe('UnifiedChat workflow team panel', () => {
  let fixture: ComponentFixture<UnifiedChatComponent>;
  let component: UnifiedChatComponent;
  let service: jasmine.SpyObj<LocalAgentChatService>;
  let savedStorage: string | null;

  beforeEach(async () => {
    savedStorage = localStorage.getItem('unified_chat_sessions');
    const bed = createWorkflowTestBed();
    service = bed.agentChatServiceSpy;
    service.getWorkflowTeam.and.returnValue(team);
    await TestBed.configureTestingModule({
      imports: [FormsModule, NoopAnimationsModule, HttpClientTestingModule, MatMenuModule],
      declarations: [UnifiedChatComponent],
      providers: bed.providers,
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();
    fixture = TestBed.createComponent(UnifiedChatComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    if (savedStorage === null) localStorage.removeItem('unified_chat_sessions');
    else localStorage.setItem('unified_chat_sessions', savedStorage);
  });

  function render(): void {
    (component as any).cdr.markForCheck();
    fixture.detectChanges();
  }
  const text = (element: Element | null | undefined): string => (element?.textContent || '').replace(/\s+/g, ' ').trim();
  const panel = (): HTMLElement | null => fixture.nativeElement.querySelector('[data-testid="workflow-team"]');
  const summary = (): string => text(panel()?.querySelector('summary'));
  // Each row's own elements; the template keeps no whitespace between them.
  const rows = (selector: string): string[][] =>
    Array.from(panel()?.querySelectorAll(selector) || []).map(row => Array.from(row.children).map(text));
  const status = (): string => text(fixture.nativeElement.querySelector('[data-testid="workflow-approval-status"]'));
  const approveButton = (): HTMLButtonElement | null => panel()?.querySelectorAll('.workflow-gate')[1]?.querySelector('button') || null;

  it('shows no team panel for a session started without a team', () => {
    service.getWorkflowTeam.and.returnValue(null);
    render();
    expect(panel()).toBeNull();
  });

  it('lays the team out as the terminal team summary does', () => {
    render();
    expect(summary()).toBe('Workflow: review (v2) · 1 gate awaiting approval');
    expect(rows('.workflow-participant')).toEqual([
      ['lead (lead) — coordinator', 'model: custom/lead-model', 'capabilities: plan, review', 'delegates to: worker'],
      ['worker — implementer', 'model: custom/worker-model', 'delegates to: nobody']
    ]);
    expect(rows('.workflow-section')).toContain(['Routing:', 'implement → worker']);
    expect(rows('.workflow-gate')).toEqual([
      ['Implementation begins after: design', 'approved'],
      ['Workflow completes after: ship', 'Approve']
    ]);
    expect(Array.from(panel()!.querySelectorAll('.workflow-section')).map(text)).toContain('Parallel workers: 1');
  });

  it('counts the gates still awaiting approval', () => {
    let current: WorkflowTeam = { ...team, gates: { ...team.gates, approved: [] } };
    service.getWorkflowTeam.and.callFake(() => current);
    render();
    expect(summary()).toBe('Workflow: review (v2) · 2 gates awaiting approval');
    current = { ...team, gates: { ...team.gates, approved: ['design', 'ship'] } };
    render();
    expect(summary()).toBe('Workflow: review (v2)');
    expect(panel()!.querySelector('.workflow-gate button')).toBeNull();
  });

  it('approves a gate for the session whose team it shows and reports the answer', async () => {
    let current = team;
    let answer!: (outcome: WorkflowApprovalOutcome) => void;
    service.getWorkflowTeam.and.callFake(() => current);
    service.approveWorkflowGate.and.returnValue(new Promise<WorkflowApprovalOutcome>(resolve => answer = resolve));
    render();
    component.newChat(); // a session to approve for, after ngOnInit has loaded the saved ones
    render();
    approveButton()!.click();
    render();

    const shownFor = service.getWorkflowTeam.calls.mostRecent().args[0];
    expect(shownFor).toBeTruthy();
    expect(service.approveWorkflowGate).toHaveBeenCalledOnceWith(shownFor!, 'ship', undefined);
    expect(approveButton()!.disabled).toBeTrue();
    expect(status()).toBe('Approving gate…');

    // The service records the approval on the team before it answers.
    current = { ...team, gates: { ...team.gates, approved: ['design', 'ship'] } };
    answer({ ok: true, message: "Gate 'ship' approved for workflow 'review'." });
    await fixture.whenStable();
    render();
    expect(status()).toBe("Gate 'ship' approved for workflow 'review'.");
    expect(rows('.workflow-gate')[1]).toEqual(['Workflow completes after: ship', 'approved']);
    expect(summary()).toBe('Workflow: review (v2)');
  });

  it('holds Approve while a run streams until its live controls are ready', () => {
    render();
    component.isStreaming = true;
    service.liveControlsReady = false;
    render();
    expect(approveButton()!.disabled).toBeTrue();
    service.liveControlsReady = true;
    render();
    expect(approveButton()!.disabled).toBeFalse();
  });
});
