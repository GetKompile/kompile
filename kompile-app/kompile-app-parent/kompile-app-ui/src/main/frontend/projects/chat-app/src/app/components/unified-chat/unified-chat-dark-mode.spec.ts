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
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { of, Subject } from 'rxjs';

import { UnifiedChatComponent } from './unified-chat.component';
import { responsiveLayout } from '../responsive-layout-test-helper';
import { AgentProvider } from '@shared/models/api-models';
import { AgentService } from '@shared/services/agent.service';
import { ChatHistoryService } from '@shared/services/chat-history.service';
import { ChatStorageService } from '@shared/services/chat-storage.service';
import { CliTranscriptService } from '@shared/services/cli-transcript.service';
import { ConversationalRagService } from '@shared/services/conversational-rag.service';
import { FolderService } from '@shared/services/folder.service';
import { HarnessActivity, LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { ModelContextService } from '@shared/services/model-context.service';
import { WebSocketService } from '@shared/services/websocket.service';

/** Service doubles for a mounted chat with one available persona (mirrors unified-chat-commands.spec.ts). */
function createTestBed() {
  const agentChatServiceSpy = jasmine.createSpyObj('LocalAgentChatService', [
    'getStreamingContent', 'getStreamingComplete', 'getStreamingError',
    'getChatStats', 'getSources', 'getFilesModified', 'sendMessage',
    'cancelStreaming', 'createSession', 'getToolUse', 'getCompaction', 'getContextBudget',
    'getCommandOutcomes'
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
  agentServiceSpy.getChatHarnessAgents.and.returnValue(of([
    { name: 'coder', displayName: 'Coder', available: true, agentType: 'HARNESS' } as AgentProvider
  ]));
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

/**
 * Buttons that should render as Material. Two kinds stay native on purpose: slash-menu entries
 * are listbox options styled by the chat tokens, and code-block copy buttons are injected into
 * rendered markdown, outside Angular.
 */
function materialCandidates(root: HTMLElement): HTMLButtonElement[] {
  return Array.from(root.querySelectorAll<HTMLButtonElement>('button'))
    .filter(button => button.getAttribute('role') !== 'option' && !button.classList.contains('code-copy-btn'));
}

function nonMaterial(root: HTMLElement): string[] {
  return materialCandidates(root)
    .filter(button => !button.classList.contains('mat-mdc-button-base'))
    .map(button => `${button.className} "${(button.textContent ?? '').trim()}"`);
}

function buttonWithText(root: HTMLElement, text: string): HTMLButtonElement | undefined {
  return Array.from(root.querySelectorAll<HTMLButtonElement>('button'))
    .find(button => (button.textContent ?? '').includes(text));
}

describe('UnifiedChatComponent Material buttons and dark mode', () => {
  let fixture: ComponentFixture<UnifiedChatComponent>;
  let component: UnifiedChatComponent;
  let host: HTMLElement;
  let savedStorage: string | null;

  beforeEach(async () => {
    savedStorage = localStorage.getItem('unified_chat_sessions');
    await TestBed.configureTestingModule({
      // MatButtonModule renders the mat-*-button attributes as Material and makes disabledInteractive
      // a real input; the other unified-chat specs leave these buttons native.
      imports: [FormsModule, NoopAnimationsModule, HttpClientTestingModule, MatMenuModule, MatButtonModule],
      declarations: [UnifiedChatComponent],
      providers: createTestBed().providers,
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();
    fixture = TestBed.createComponent(UnifiedChatComponent);
    component = fixture.componentInstance;
    host = fixture.nativeElement as HTMLElement;
    component.selectedAgent = { name: 'coder', displayName: 'Coder' } as AgentProvider;
  });

  afterEach(() => {
    document.body.classList.remove('dark-theme', 'light-theme');
    document.body.style.removeProperty('--color-primary');
    document.body.style.removeProperty('--color-primary-dark');
    if (savedStorage === null) localStorage.removeItem('unified_chat_sessions');
    else localStorage.setItem('unified_chat_sessions', savedStorage);
  });

  function rerender(): void {
    (component as any).cdr.markForCheck();
    fixture.detectChanges();
  }

  /** Puts the composer into a live harness turn reporting the given activity. */
  function showHarnessActivity(activity: HarnessActivity): void {
    component.isStreaming = true;
    const service = TestBed.inject(LocalAgentChatService);
    service.liveControlsReady = true;
    service.harnessActivity = activity;
    rerender();
  }

  for (const width of [320, 375, 768, 1280]) {
    it(`keeps a full-width composer inside its chat pane at ${width}px`, () => {
      component.workspaceChat = { id: 'mobile-chat', name: 'Mobile chat' };
      fixture.detectChanges();
      component.showHistorySidebar = false;
      rerender();
      const layout = responsiveLayout(host, width, 480);
      try {
        const wrapper = layout.root.querySelector('.unified-chat-wrapper') as HTMLElement;
        const input = layout.root.querySelector('textarea') as HTMLElement;
        const composer = layout.root.querySelector('.input-area') as HTMLElement;
        expect(wrapper.getBoundingClientRect().bottom).toBeLessThanOrEqual(480);
        expect(composer.getBoundingClientRect().bottom).toBeLessThanOrEqual(wrapper.getBoundingClientRect().bottom + 1);
        expect(layout.root.scrollWidth).toBeLessThanOrEqual(width);
        expect(input.getBoundingClientRect().right).toBeLessThanOrEqual(width);
        if (width <= 768) {
          const container = layout.root.querySelector('.input-container') as HTMLElement;
          expect(input.getBoundingClientRect().width).toBeCloseTo(container.getBoundingClientRect().width, 0);
          expect(layout.view.getComputedStyle(input).fontSize).toBe('16px');
          for (const button of Array.from(container.querySelectorAll<HTMLElement>('button'))) {
            expect(button.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
          }
        }
      } finally { layout.dispose(); }
    });
  }

  it('keeps send and stop controls reachable together on a narrow live chat', () => {
    component.workspaceChat = { id: 'mobile-chat', name: 'Mobile chat' };
    fixture.detectChanges();
    component.showHistorySidebar = false;
    showHarnessActivity({ backgroundable: true, turnActive: true, tasks: [], processes: [], subagents: [] });
    const layout = responsiveLayout(host, 320);
    try {
      for (const selector of ['.send-btn', '.stop-btn']) {
        const button = layout.root.querySelector(selector) as HTMLElement;
        expect(button).not.toBeNull();
        expect(button.getBoundingClientRect().right).toBeLessThanOrEqual(320);
        expect(button.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
      }
    } finally { layout.dispose(); }
  });

  it('renders every chat button through Angular Material', () => {
    fixture.detectChanges();
    expect(materialCandidates(host).length).toBeGreaterThan(0);
    expect(nonMaterial(host)).toEqual([]);
  });

  it('renders the live harness controls through Angular Material', () => {
    fixture.detectChanges();
    showHarnessActivity({
      backgroundable: true, turnActive: true, tasks: [],
      processes: [{ id: 'proc-1', description: 'build', state: 'RUNNING' }],
      subagents: [{ id: 'child-1', type: 'coder', description: 'review', state: 'thinking',
        running: true, canSend: true, canCancel: true }]
    });
    expect(buttonWithText(host, 'Stop process')).toBeDefined();
    expect(buttonWithText(host, 'Send to child')).toBeDefined();
    expect(nonMaterial(host)).toEqual([]);
  });

  it('names every icon-only button for hover and screen readers', () => {
    fixture.detectChanges();
    const iconButtons = Array.from(host.querySelectorAll<HTMLButtonElement>('.mat-mdc-icon-button, .mat-mdc-mini-fab'));
    expect(iconButtons.length).toBeGreaterThan(0);
    expect(iconButtons.filter(button => !button.title && !button.getAttribute('aria-label'))
      .map(button => button.className)).toEqual([]);
  });

  it('titles each welcome command chip with the CLI description of its command', () => {
    fixture.detectChanges();
    const chips = Array.from(host.querySelectorAll<HTMLButtonElement>('.welcome-command-chip'));
    expect(chips.map(chip => (chip.textContent ?? '').trim())).toEqual(component.slashCommands.map(item => item.command));
    expect(chips.map(chip => chip.title)).toEqual(component.slashCommands.map(item => item.description));
    chips[0].click();
    expect(component.userInput).toBe(component.slashCommands[0].command + ' ');
  });

  it('keeps chip titles hoverable but ignores clicks before a persona is selected', () => {
    fixture.detectChanges();
    component.selectedAgent = null;
    rerender();
    const chip = host.querySelector<HTMLButtonElement>('.welcome-command-chip')!;
    // A natively disabled Material button gets pointer-events: none, which also hides its title.
    expect(chip.disabled).toBeFalse();
    expect(chip.getAttribute('aria-disabled')).toBe('true');
    expect(getComputedStyle(chip).pointerEvents).not.toBe('none');
    expect(chip.title).toBe(component.slashCommands[0].description);
    const before = component.userInput;
    chip.click();
    expect(component.userInput).toBe(before);
  });

  it('shows why a shared process cannot be stopped and never sends its kill', () => {
    fixture.detectChanges();
    showHarnessActivity({
      backgroundable: false, turnActive: true, tasks: [],
      processes: [{ id: 'proc-2', description: 'shared build', state: 'RUNNING', kind: 'shared',
        killable: false, owner: 'session-b' }]
    });
    const send = spyOn(component, 'sendHarnessControl').and.resolveTo();
    const stop = buttonWithText(host, 'Stop process')!;
    expect(stop.disabled).toBeFalse();
    expect(stop.getAttribute('aria-disabled')).toBe('true');
    expect(stop.title).toBe('Shared by session-b; only its owner can stop it');
    stop.click();
    expect(send).not.toHaveBeenCalled();
  });

  it('applies the dark color scheme and accent fill only under the dark theme', () => {
    fixture.detectChanges();
    document.body.style.setProperty('--color-primary', 'rgb(1, 2, 3)');
    document.body.style.setProperty('--color-primary-dark', 'rgb(4, 5, 6)');
    const style = () => getComputedStyle(host);

    expect(style().getPropertyValue('color-scheme').trim()).not.toBe('dark');
    expect(style().getPropertyValue('--chat-accent-fill').trim()).toBe('rgb(1, 2, 3)');

    document.body.classList.add('dark-theme');
    expect(style().getPropertyValue('color-scheme').trim()).toBe('dark');
    // User bubbles carry white text, so they take the darker primary rather than the light text tone.
    expect(style().getPropertyValue('--chat-accent-fill').trim()).toBe('rgb(4, 5, 6)');

    document.body.classList.replace('dark-theme', 'light-theme');
    expect(style().getPropertyValue('color-scheme').trim()).not.toBe('dark');
  });
});
