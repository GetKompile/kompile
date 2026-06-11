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
import { BehaviorSubject, Subject, of } from 'rxjs';

import { PassthroughChatComponent } from './passthrough-chat.component';
import { PassthroughChatService } from '../../services/passthrough-chat.service';
import { AgentService } from '../../services/agent.service';
import { AgentProvider, PassthroughMessage } from '../../models/api-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

/** Creates an AgentProvider with sensible defaults. */
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
    description: 'Test CLI agent',
    agentType: 'CLI',
    ...overrides
  };
}

/** Creates a PassthroughMessage with sensible defaults. */
function mockMessage(role: 'USER' | 'ASSISTANT', content: string): PassthroughMessage {
  return { role, content, timestamp: new Date().toISOString() };
}

/** Creates all spied services and returns the spy objects plus a provider array. */
function createTestBed() {
  // Real BehaviorSubjects / Subjects so subscriptions in the component work
  const connectedSubject = new BehaviorSubject<boolean>(false);
  const agentReadySubject = new BehaviorSubject<boolean>(false);
  const messagesSubject = new BehaviorSubject<PassthroughMessage[]>([]);
  const streamingContentSubject = new BehaviorSubject<string>('');
  const errorSubject = new Subject<string>();

  const passthroughServiceSpy = jasmine.createSpyObj('PassthroughChatService', [
    'connect', 'disconnect', 'sendMessage', 'clearMessages'
  ], {
    connected$: connectedSubject.asObservable(),
    agentReady$: agentReadySubject.asObservable(),
    messages$: messagesSubject.asObservable(),
    streamingContent$: streamingContentSubject.asObservable(),
    error$: errorSubject.asObservable()
  });

  const agentServiceSpy = jasmine.createSpyObj('AgentService', [
    'getAllAgents', 'getAvailableAgents'
  ]);
  agentServiceSpy.getAllAgents.and.returnValue(of([]));

  return {
    passthroughServiceSpy,
    agentServiceSpy,
    // Expose subjects so individual tests can push values
    connectedSubject,
    agentReadySubject,
    messagesSubject,
    streamingContentSubject,
    errorSubject,
    providers: [
      { provide: PassthroughChatService, useValue: passthroughServiceSpy },
      { provide: AgentService, useValue: agentServiceSpy }
    ]
  };
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('PassthroughChatComponent', () => {
  let component: PassthroughChatComponent;
  let fixture: ComponentFixture<PassthroughChatComponent>;
  let spies: ReturnType<typeof createTestBed>;

  beforeEach(async () => {
    spies = createTestBed();

    await TestBed.configureTestingModule({
      // Standalone component — must go in imports, not declarations
      imports: [PassthroughChatComponent, FormsModule, NoopAnimationsModule, HttpClientTestingModule],
      providers: spies.providers,
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(PassthroughChatComponent);
    component = fixture.componentInstance;
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. COMPONENT CREATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Component creation', () => {
    it('should create', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should initialize with default values', () => {
      expect(component.connected).toBeFalse();
      expect(component.agentReady).toBeFalse();
      expect(component.messages).toEqual([]);
      expect(component.streamingContent).toBe('');
      expect(component.userInput).toBe('');
      expect(component.errorMessage).toBe('');
      expect(component.skipPermissions).toBeTrue();
      expect(component.injectMcpTools).toBeTrue();
    });

    it('should call loadAgents on init', () => {
      fixture.detectChanges(); // triggers ngOnInit
      expect(spies.agentServiceSpy.getAllAgents).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. loadAgents()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadAgents()', () => {
    it('should populate agents from service', () => {
      const cliAgent = mockAgent({ name: 'agent-1', available: true, agentType: 'CLI' });
      spies.agentServiceSpy.getAllAgents.and.returnValue(of([cliAgent]));

      fixture.detectChanges();

      expect(component.agents.length).toBe(1);
      expect(component.agents[0].name).toBe('agent-1');
    });

    it('should filter out unavailable agents', () => {
      const available = mockAgent({ name: 'ok-agent', available: true, agentType: 'CLI' });
      const unavailable = mockAgent({ name: 'bad-agent', available: false, agentType: 'CLI' });
      spies.agentServiceSpy.getAllAgents.and.returnValue(of([available, unavailable]));

      fixture.detectChanges();

      expect(component.agents.length).toBe(1);
      expect(component.agents[0].name).toBe('ok-agent');
    });

    it('should filter out API-type agents', () => {
      const cliAgent = mockAgent({ name: 'cli-agent', available: true, agentType: 'CLI' });
      const apiAgent = mockAgent({ name: 'api-agent', available: true, agentType: 'API' });
      spies.agentServiceSpy.getAllAgents.and.returnValue(of([cliAgent, apiAgent]));

      fixture.detectChanges();

      expect(component.agents.length).toBe(1);
      expect(component.agents[0].name).toBe('cli-agent');
    });

    it('should auto-select first agent when none is selected', () => {
      const agent1 = mockAgent({ name: 'first-agent', available: true, agentType: 'CLI' });
      const agent2 = mockAgent({ name: 'second-agent', available: true, agentType: 'CLI' });
      spies.agentServiceSpy.getAllAgents.and.returnValue(of([agent1, agent2]));

      fixture.detectChanges();

      expect(component.selectedAgent).toBe('first-agent');
    });

    it('should not override existing selectedAgent', () => {
      component.selectedAgent = 'already-selected';
      const agent = mockAgent({ name: 'other-agent', available: true, agentType: 'CLI' });
      spies.agentServiceSpy.getAllAgents.and.returnValue(of([agent]));

      fixture.detectChanges();

      expect(component.selectedAgent).toBe('already-selected');
    });

    it('should leave selectedAgent empty when no agents are available', () => {
      spies.agentServiceSpy.getAllAgents.and.returnValue(of([]));

      fixture.detectChanges();

      expect(component.selectedAgent).toBe('');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. connect()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('connect()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      component.selectedAgent = 'kompile-local';
    });

    it('should call passthroughService.connect() with correct params', () => {
      component.skipPermissions = true;
      component.injectMcpTools = false;
      component.workingDirectory = '/home/user/project';

      component.connect();

      expect(spies.passthroughServiceSpy.connect).toHaveBeenCalledWith(jasmine.objectContaining({
        agentName: 'kompile-local',
        skipPermissions: true,
        injectMcpTools: false,
        workingDirectory: '/home/user/project'
      }));
    });

    it('should pass workingDirectory as undefined when empty string', () => {
      component.workingDirectory = '';

      component.connect();

      const callArg = spies.passthroughServiceSpy.connect.calls.mostRecent().args[0];
      expect(callArg.workingDirectory).toBeUndefined();
    });

    it('should clear messages before connecting', () => {
      component.connect();

      expect(spies.passthroughServiceSpy.clearMessages).toHaveBeenCalled();
    });

    it('should clear error message before connecting', () => {
      component.errorMessage = 'Previous error';

      component.connect();

      expect(component.errorMessage).toBe('');
    });

    it('should not connect when no agent is selected', () => {
      component.selectedAgent = '';

      component.connect();

      expect(spies.passthroughServiceSpy.connect).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. disconnect()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('disconnect()', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should call passthroughService.disconnect()', () => {
      component.disconnect();

      expect(spies.passthroughServiceSpy.disconnect).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. sendMessage()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('sendMessage()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      // Simulate agent ready
      spies.agentReadySubject.next(true);
    });

    it('should call passthroughService.sendMessage() with trimmed message', () => {
      component.userInput = '  Hello World  ';

      component.sendMessage();

      expect(spies.passthroughServiceSpy.sendMessage).toHaveBeenCalledWith('Hello World');
    });

    it('should clear userInput after sending', () => {
      component.userInput = 'Hello';

      component.sendMessage();

      expect(component.userInput).toBe('');
    });

    it('should clear errorMessage before sending', () => {
      component.errorMessage = 'Some previous error';
      component.userInput = 'Hello';

      component.sendMessage();

      expect(component.errorMessage).toBe('');
    });

    it('should not send when agent is not ready', () => {
      // Override: agent not ready
      spies.agentReadySubject.next(false);
      component.userInput = 'Hello';

      component.sendMessage();

      expect(spies.passthroughServiceSpy.sendMessage).not.toHaveBeenCalled();
    });

    it('should not send empty messages', () => {
      component.userInput = '   ';

      component.sendMessage();

      expect(spies.passthroughServiceSpy.sendMessage).not.toHaveBeenCalled();
    });

    it('should not send blank string', () => {
      component.userInput = '';

      component.sendMessage();

      expect(spies.passthroughServiceSpy.sendMessage).not.toHaveBeenCalled();
    });

    it('should not send when both empty and agent not ready', () => {
      spies.agentReadySubject.next(false);
      component.userInput = '';

      component.sendMessage();

      expect(spies.passthroughServiceSpy.sendMessage).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. onKeyDown()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('onKeyDown()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      spies.agentReadySubject.next(true);
      component.userInput = 'Test message';
    });

    it('should send message on Enter key', () => {
      const event = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: false });
      spyOn(event, 'preventDefault');

      component.onKeyDown(event);

      expect(event.preventDefault).toHaveBeenCalled();
      expect(spies.passthroughServiceSpy.sendMessage).toHaveBeenCalled();
    });

    it('should NOT send message on Shift+Enter', () => {
      const event = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true });
      spyOn(event, 'preventDefault');

      component.onKeyDown(event);

      expect(event.preventDefault).not.toHaveBeenCalled();
      expect(spies.passthroughServiceSpy.sendMessage).not.toHaveBeenCalled();
    });

    it('should NOT send on other keys', () => {
      const event = new KeyboardEvent('keydown', { key: 'ArrowUp', shiftKey: false });

      component.onKeyDown(event);

      expect(spies.passthroughServiceSpy.sendMessage).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. Observable subscriptions
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Observable subscriptions', () => {
    beforeEach(() => {
      fixture.detectChanges(); // subscribe in ngOnInit
    });

    it('should update connected from connected$', () => {
      expect(component.connected).toBeFalse();

      spies.connectedSubject.next(true);

      expect(component.connected).toBeTrue();
    });

    it('should update agentReady from agentReady$', () => {
      expect(component.agentReady).toBeFalse();

      spies.agentReadySubject.next(true);

      expect(component.agentReady).toBeTrue();
    });

    it('should update messages from messages$', () => {
      const msgs: PassthroughMessage[] = [mockMessage('USER', 'Hello')];

      spies.messagesSubject.next(msgs);

      expect(component.messages.length).toBe(1);
      expect(component.messages[0].content).toBe('Hello');
    });

    it('should update streamingContent from streamingContent$', () => {
      spies.streamingContentSubject.next('Partial response...');

      expect(component.streamingContent).toBe('Partial response...');
    });

    it('should update errorMessage from error$', () => {
      spies.errorSubject.next('WebSocket disconnected');

      expect(component.errorMessage).toBe('WebSocket disconnected');
    });

    it('should reflect multiple message updates', () => {
      spies.messagesSubject.next([mockMessage('USER', 'First')]);
      expect(component.messages.length).toBe(1);

      spies.messagesSubject.next([
        mockMessage('USER', 'First'),
        mockMessage('ASSISTANT', 'Reply')
      ]);
      expect(component.messages.length).toBe(2);
      expect(component.messages[1].role).toBe('ASSISTANT');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. Connection state management
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Connection state management', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should reflect disconnected state initially', () => {
      expect(component.connected).toBeFalse();
      expect(component.agentReady).toBeFalse();
    });

    it('should reflect connected + agent-ready state after service emits', () => {
      spies.connectedSubject.next(true);
      spies.agentReadySubject.next(true);

      expect(component.connected).toBeTrue();
      expect(component.agentReady).toBeTrue();
    });

    it('should reflect agent-not-ready after turn starts', () => {
      spies.agentReadySubject.next(true);
      expect(component.agentReady).toBeTrue();

      spies.agentReadySubject.next(false);
      expect(component.agentReady).toBeFalse();
    });

    it('should call disconnect on destroy if connected', () => {
      spies.connectedSubject.next(true);

      component.ngOnDestroy();

      expect(spies.passthroughServiceSpy.disconnect).toHaveBeenCalled();
    });

    it('should NOT call disconnect on destroy if not connected', () => {
      spies.connectedSubject.next(false);

      component.ngOnDestroy();

      expect(spies.passthroughServiceSpy.disconnect).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. UI state
  // ─────────────────────────────────────────────────────────────────────────────

  describe('UI state — connected vs disconnected', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should show disconnected state (connected=false) initially', () => {
      expect(component.connected).toBeFalse();
    });

    it('should show connected state after service emits true', () => {
      spies.connectedSubject.next(true);
      fixture.detectChanges();

      expect(component.connected).toBeTrue();
    });

    it('should correctly track agent selection', () => {
      expect(component.selectedAgent).toBe('');

      component.selectedAgent = 'kompile-local';

      expect(component.selectedAgent).toBe('kompile-local');
    });

    it('should expose agents list for template binding', () => {
      const agent = mockAgent({ name: 'test-agent', available: true, agentType: 'CLI' });
      spies.agentServiceSpy.getAllAgents.and.returnValue(of([agent]));

      component.loadAgents();

      expect(component.agents).toContain(jasmine.objectContaining({ name: 'test-agent' }));
    });

    it('should expose workingDirectory for template binding', () => {
      component.workingDirectory = '/projects/myapp';
      expect(component.workingDirectory).toBe('/projects/myapp');
    });

    it('should expose skipPermissions toggle for template binding', () => {
      component.skipPermissions = false;
      expect(component.skipPermissions).toBeFalse();

      component.skipPermissions = true;
      expect(component.skipPermissions).toBeTrue();
    });

    it('should expose injectMcpTools toggle for template binding', () => {
      component.injectMcpTools = false;
      expect(component.injectMcpTools).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. Full conversation simulation
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Full conversation simulation', () => {
    beforeEach(() => {
      const cliAgent = mockAgent({ name: 'kompile-local', available: true, agentType: 'CLI' });
      spies.agentServiceSpy.getAllAgents.and.returnValue(of([cliAgent]));
      fixture.detectChanges();
    });

    it('should reflect a full connect → send → receive cycle', fakeAsync(() => {
      // Step 1: connect
      component.connect();
      expect(spies.passthroughServiceSpy.connect).toHaveBeenCalled();

      // Step 2: agent becomes ready
      spies.connectedSubject.next(true);
      spies.agentReadySubject.next(true);
      tick();

      expect(component.connected).toBeTrue();
      expect(component.agentReady).toBeTrue();

      // Step 3: user sends a message
      component.userInput = 'What is RAG?';
      component.sendMessage();

      expect(spies.passthroughServiceSpy.sendMessage).toHaveBeenCalledWith('What is RAG?');
      expect(component.userInput).toBe('');

      // Step 4: assistant streaming arrives
      spies.streamingContentSubject.next('RAG stands for Retrieval...');
      tick();
      expect(component.streamingContent).toBe('RAG stands for Retrieval...');

      // Step 5: turn completes — messages array updated, streaming cleared
      spies.messagesSubject.next([
        mockMessage('USER', 'What is RAG?'),
        mockMessage('ASSISTANT', 'RAG stands for Retrieval Augmented Generation.')
      ]);
      spies.streamingContentSubject.next('');
      spies.agentReadySubject.next(true);
      tick();

      expect(component.messages.length).toBe(2);
      expect(component.messages[1].role).toBe('ASSISTANT');
      expect(component.streamingContent).toBe('');
      expect(component.agentReady).toBeTrue();
    }));

    it('should display error messages from service', fakeAsync(() => {
      spies.connectedSubject.next(true);
      spies.agentReadySubject.next(true);
      tick();

      spies.errorSubject.next('Agent process crashed unexpectedly');
      tick();

      expect(component.errorMessage).toBe('Agent process crashed unexpectedly');
    }));
  });
});
