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
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ReactiveFormsModule, FormBuilder } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';

import { KClawChatComponent } from './kclaw-chat.component';
import { KClawService } from '../../services/kclaw.service';
import { AgentDefinition, KClawChatResponse } from '../../models/kclaw-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

/** Creates an AgentDefinition with sensible defaults. */
function mockAgentDef(overrides: Partial<AgentDefinition> = {}): AgentDefinition {
  return {
    name: 'jarvis',
    description: 'Default AI agent',
    systemPrompt: 'You are Jarvis.',
    tools: [],
    maxSteps: 10,
    isDefault: true,
    ...overrides
  };
}

/** Creates a mock chat response. */
function mockChatResponse(overrides: Partial<KClawChatResponse> = {}): KClawChatResponse {
  return {
    response: 'This is a response.',
    sessionKey: 'session:abc123',
    agentId: 'jarvis',
    success: true,
    tokenUsage: { inputTokens: 50, outputTokens: 30 },
    ...overrides
  };
}

/** Creates spied services and returns them with provider array. */
function createTestBed() {
  // Real BehaviorSubject so component subscriptions work
  const agentsSubject = new BehaviorSubject<AgentDefinition[]>([]);

  const kClawServiceSpy = jasmine.createSpyObj('KClawService', [
    'chat', 'chatStream', 'getAgents'
  ], {
    agents$: agentsSubject.asObservable()
  });

  // Default return values
  kClawServiceSpy.chat.and.returnValue(of(mockChatResponse()));
  kClawServiceSpy.chatStream.and.returnValue(of('streaming chunk'));
  kClawServiceSpy.getAgents.and.returnValue(of([]));

  return {
    kClawServiceSpy,
    agentsSubject,
    providers: [
      { provide: KClawService, useValue: kClawServiceSpy },
      FormBuilder
    ]
  };
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('KClawChatComponent', () => {
  let component: KClawChatComponent;
  let fixture: ComponentFixture<KClawChatComponent>;
  let spies: ReturnType<typeof createTestBed>;

  beforeEach(async () => {
    spies = createTestBed();

    await TestBed.configureTestingModule({
      // Non-standalone component — goes in declarations
      declarations: [KClawChatComponent],
      imports: [ReactiveFormsModule, NoopAnimationsModule, HttpClientTestingModule],
      providers: spies.providers,
      schemas: [NO_ERRORS_SCHEMA]
    })
      // Override template to fix formControlName outside formGroup
      .overrideComponent(KClawChatComponent, {
        set: {
          template: `
            <div class="kclaw-chat" [formGroup]="chatForm">
              <div class="agent-selector">
                <select formControlName="agentId">
                  <option *ngFor="let agent of agents" [value]="agent.name">{{ agent.name }}</option>
                </select>
              </div>
              <div class="messages-container">
                <div *ngFor="let msg of messages" class="message" [class.user]="msg.role === 'user'">
                  <div class="message-content">{{ msg.content }}</div>
                </div>
              </div>
              <form (ngSubmit)="sendMessage()">
                <textarea formControlName="message"></textarea>
                <input type="checkbox" formControlName="stream"> Stream
                <button type="submit" [disabled]="chatForm.invalid || isLoading">Send</button>
              </form>
            </div>`
        }
      })
      .compileComponents();

    fixture = TestBed.createComponent(KClawChatComponent);
    component = fixture.componentInstance;
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. COMPONENT CREATION & FORM INITIALIZATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Component creation and form initialization', () => {
    it('should create', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should initialize chatForm with default values', () => {
      fixture.detectChanges();

      expect(component.chatForm).toBeTruthy();
      expect(component.chatForm.value.message).toBe('');
      expect(component.chatForm.value.agentId).toBe('jarvis');
      expect(component.chatForm.value.stream).toBeTrue();
    });

    it('should mark message field as required', () => {
      fixture.detectChanges();
      const messageCtrl = component.chatForm.get('message');

      messageCtrl!.setValue('');
      expect(messageCtrl!.valid).toBeFalse();

      messageCtrl!.setValue('hello');
      expect(messageCtrl!.valid).toBeTrue();
    });

    it('should initialize with empty messages array', () => {
      fixture.detectChanges();
      expect(component.messages).toEqual([]);
    });

    it('should initialize with isLoading and isStreaming false', () => {
      fixture.detectChanges();
      expect(component.isLoading).toBeFalse();
      expect(component.isStreaming).toBeFalse();
    });

    it('should generate a session key on init', () => {
      fixture.detectChanges();
      expect(component.sessionKey).toBeTruthy();
      expect(component.sessionKey).toMatch(/^session:/);
    });

    it('should subscribe to agents$ on init', () => {
      const agent = mockAgentDef({ name: 'my-agent' });
      spies.agentsSubject.next([agent]);

      fixture.detectChanges();

      expect(component.agents.length).toBe(1);
      expect(component.agents[0].name).toBe('my-agent');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. SESSION KEY GENERATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Session key generation', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should generate key in format session:<base36><random>', () => {
      expect(component.sessionKey).toMatch(/^session:[a-z0-9]+$/);
    });

    it('should generate unique keys across calls', () => {
      const first = component.sessionKey;
      component.newSession();
      const second = component.sessionKey;

      // Statistically guaranteed to differ (Date.now() component)
      expect(first).not.toBe(second);
    });

    it('should start with "session:" prefix', () => {
      expect(component.sessionKey.startsWith('session:')).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. selectAgent()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('selectAgent()', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should set selectedAgent', () => {
      const agent = mockAgentDef({ name: 'custom-agent' });
      component.selectAgent(agent);

      expect(component.selectedAgent).toBe(agent);
    });

    it('should patch chatForm agentId with selected agent name', () => {
      const agent = mockAgentDef({ name: 'specialist-agent' });
      component.selectAgent(agent);

      expect(component.chatForm.value.agentId).toBe('specialist-agent');
    });

    it('should auto-select default agent when agents$ emits', () => {
      const defaultAgent = mockAgentDef({ name: 'default-agent', isDefault: true });
      const otherAgent = mockAgentDef({ name: 'other-agent', isDefault: false });

      spies.agentsSubject.next([otherAgent, defaultAgent]);

      expect(component.selectedAgent).toBe(defaultAgent);
    });

    it('should fallback to first agent when no default exists', () => {
      const agentA = mockAgentDef({ name: 'agent-a', isDefault: false });
      const agentB = mockAgentDef({ name: 'agent-b', isDefault: false });

      spies.agentsSubject.next([agentA, agentB]);

      expect(component.selectedAgent).toBe(agentA);
    });

    it('should not override existing selectedAgent on subsequent emissions', () => {
      const first = mockAgentDef({ name: 'first', isDefault: true });
      spies.agentsSubject.next([first]);
      expect(component.selectedAgent).toBe(first);

      // Second emission — selectedAgent already set, should not override
      const second = mockAgentDef({ name: 'second', isDefault: true });
      spies.agentsSubject.next([first, second]);
      expect(component.selectedAgent).toBe(first);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. newSession()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('newSession()', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should clear messages', () => {
      // Simulate some messages already present via sync mode
      spies.kClawServiceSpy.chat.and.returnValue(of(mockChatResponse()));
      component.chatForm.patchValue({ message: 'Hello', stream: false });
      component.sendMessage();

      expect(component.messages.length).toBeGreaterThan(0);

      component.newSession();

      expect(component.messages).toEqual([]);
    });

    it('should generate a new session key', () => {
      const oldKey = component.sessionKey;
      component.newSession();

      expect(component.sessionKey).not.toBe(oldKey);
      expect(component.sessionKey).toMatch(/^session:[a-z0-9]+$/);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. sendMessage() — general guards
  // ─────────────────────────────────────────────────────────────────────────────

  describe('sendMessage() — form guards', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should not send when form is invalid (empty message)', () => {
      component.chatForm.patchValue({ message: '' });

      component.sendMessage();

      expect(spies.kClawServiceSpy.chat).not.toHaveBeenCalled();
      expect(spies.kClawServiceSpy.chatStream).not.toHaveBeenCalled();
    });

    it('should not send while isLoading is true', () => {
      component.chatForm.patchValue({ message: 'Hello' });
      component.isLoading = true;

      component.sendMessage();

      expect(spies.kClawServiceSpy.chat).not.toHaveBeenCalled();
    });

    it('should set isLoading to true before dispatching', () => {
      // Use a subject that never completes so we can inspect mid-flight state
      const pending = new Subject<KClawChatResponse>();
      spies.kClawServiceSpy.chat.and.returnValue(pending.asObservable());
      component.chatForm.patchValue({ message: 'Hi', stream: false });

      component.sendMessage();

      expect(component.isLoading).toBeTrue();
    });

    it('should add user message to messages array', () => {
      spies.kClawServiceSpy.chat.and.returnValue(of(mockChatResponse()));
      component.chatForm.patchValue({ message: 'Hello World', stream: false });

      component.sendMessage();

      expect(component.messages[0].role).toBe('user');
      expect(component.messages[0].content).toBe('Hello World');
    });

    it('should clear the message form control after sending', () => {
      spies.kClawServiceSpy.chat.and.returnValue(of(mockChatResponse()));
      component.chatForm.patchValue({ message: 'Test', stream: false });

      component.sendMessage();

      expect(component.chatForm.value.message).toBe('');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. sendMessage() — sync mode
  // ─────────────────────────────────────────────────────────────────────────────

  describe('sendMessage() — sync mode', () => {
    beforeEach(() => {
      fixture.detectChanges();
      component.chatForm.patchValue({ stream: false });
    });

    it('should call kClawService.chat() in sync mode', () => {
      component.chatForm.patchValue({ message: 'What is ML?', agentId: 'jarvis' });

      component.sendMessage();

      expect(spies.kClawServiceSpy.chat).toHaveBeenCalledWith(jasmine.objectContaining({
        message: 'What is ML?',
        agentId: 'jarvis',
        stream: false
      }));
    });

    it('should include sessionKey in sync chat request', () => {
      component.chatForm.patchValue({ message: 'Hello' });
      const expectedKey = component.sessionKey;

      component.sendMessage();

      const callArg = spies.kClawServiceSpy.chat.calls.mostRecent().args[0];
      expect(callArg.sessionKey).toBe(expectedKey);
    });

    it('should add assistant message on successful response', () => {
      const response = mockChatResponse({ response: 'I am an assistant.' });
      spies.kClawServiceSpy.chat.and.returnValue(of(response));
      component.chatForm.patchValue({ message: 'Hello' });

      component.sendMessage();

      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg).toBeTruthy();
      expect(assistantMsg!.content).toBe('I am an assistant.');
    });

    it('should attach tokenUsage to assistant message', () => {
      const response = mockChatResponse({
        response: 'Hello!',
        tokenUsage: { inputTokens: 100, outputTokens: 50 }
      });
      spies.kClawServiceSpy.chat.and.returnValue(of(response));
      component.chatForm.patchValue({ message: 'Hi' });

      component.sendMessage();

      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg!.tokenUsage!.inputTokens).toBe(100);
      expect(assistantMsg!.tokenUsage!.outputTokens).toBe(50);
    });

    it('should reset isLoading after response', () => {
      spies.kClawServiceSpy.chat.and.returnValue(of(mockChatResponse()));
      component.chatForm.patchValue({ message: 'Hello' });

      component.sendMessage();

      expect(component.isLoading).toBeFalse();
    });

    it('should add error message on HTTP error', () => {
      spies.kClawServiceSpy.chat.and.returnValue(
        throwError(() => new Error('HTTP 500'))
      );
      component.chatForm.patchValue({ message: 'Broken' });

      component.sendMessage();

      const errorMsg = component.messages.find(m => m.role === 'error');
      expect(errorMsg).toBeTruthy();
      expect(errorMsg!.content).toContain('HTTP 500');
    });

    it('should reset isLoading on error', () => {
      spies.kClawServiceSpy.chat.and.returnValue(
        throwError(() => new Error('Network error'))
      );
      component.chatForm.patchValue({ message: 'Broken' });

      component.sendMessage();

      expect(component.isLoading).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. sendMessage() — streaming mode
  // ─────────────────────────────────────────────────────────────────────────────

  describe('sendMessage() — streaming mode', () => {
    beforeEach(() => {
      fixture.detectChanges();
      component.chatForm.patchValue({ stream: true });
    });

    it('should call kClawService.chatStream() in streaming mode', () => {
      spies.kClawServiceSpy.chatStream.and.returnValue(of(''));
      component.chatForm.patchValue({ message: 'Stream me', agentId: 'jarvis' });

      component.sendMessage();

      expect(spies.kClawServiceSpy.chatStream).toHaveBeenCalledWith(jasmine.objectContaining({
        message: 'Stream me',
        agentId: 'jarvis',
        stream: true
      }));
    });

    it('should include sessionKey in streaming chat request', () => {
      spies.kClawServiceSpy.chatStream.and.returnValue(of(''));
      component.chatForm.patchValue({ message: 'Hello' });
      const expectedKey = component.sessionKey;

      component.sendMessage();

      const callArg = spies.kClawServiceSpy.chatStream.calls.mostRecent().args[0];
      expect(callArg.sessionKey).toBe(expectedKey);
    });

    it('should set isStreaming to true when streaming begins', () => {
      const streamSubject = new Subject<string>();
      spies.kClawServiceSpy.chatStream.and.returnValue(streamSubject.asObservable());
      component.chatForm.patchValue({ message: 'Go' });

      component.sendMessage();

      expect(component.isStreaming).toBeTrue();
    });

    it('should add a streaming placeholder assistant message', () => {
      const streamSubject = new Subject<string>();
      spies.kClawServiceSpy.chatStream.and.returnValue(streamSubject.asObservable());
      component.chatForm.patchValue({ message: 'Go' });

      component.sendMessage();

      const streamingMsg = component.messages.find(m => m.role === 'assistant' && m.isStreaming);
      expect(streamingMsg).toBeTruthy();
      expect(streamingMsg!.content).toBe('');
    });

    it('should append each SSE chunk to the streaming message', () => {
      const streamSubject = new Subject<string>();
      spies.kClawServiceSpy.chatStream.and.returnValue(streamSubject.asObservable());
      component.chatForm.patchValue({ message: 'Stream' });
      component.sendMessage();

      const placeholder = component.messages.find(m => m.isStreaming)!;
      streamSubject.next('Hello ');
      expect(placeholder.content).toBe('Hello ');

      streamSubject.next('World');
      expect(placeholder.content).toBe('Hello World');
    });

    it('should finalize streaming message (isStreaming=false) on complete', () => {
      const streamSubject = new Subject<string>();
      spies.kClawServiceSpy.chatStream.and.returnValue(streamSubject.asObservable());
      component.chatForm.patchValue({ message: 'Done' });
      component.sendMessage();

      streamSubject.next('Final content');
      streamSubject.complete();

      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg!.isStreaming).toBeFalse();
    });

    it('should reset isStreaming and isLoading on stream complete', () => {
      const streamSubject = new Subject<string>();
      spies.kClawServiceSpy.chatStream.and.returnValue(streamSubject.asObservable());
      component.chatForm.patchValue({ message: 'Done' });
      component.sendMessage();

      streamSubject.complete();

      expect(component.isStreaming).toBeFalse();
      expect(component.isLoading).toBeFalse();
    });

    it('should handle streaming error by updating placeholder with error text', () => {
      const streamSubject = new Subject<string>();
      spies.kClawServiceSpy.chatStream.and.returnValue(streamSubject.asObservable());
      component.chatForm.patchValue({ message: 'Fail' });
      component.sendMessage();

      streamSubject.next('Partial');
      streamSubject.error(new Error('SSE connection lost'));

      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg!.content).toContain('SSE connection lost');
    });

    it('should reset isStreaming and isLoading on streaming error', () => {
      const streamSubject = new Subject<string>();
      spies.kClawServiceSpy.chatStream.and.returnValue(streamSubject.asObservable());
      component.chatForm.patchValue({ message: 'Fail' });
      component.sendMessage();

      streamSubject.error(new Error('connection error'));

      expect(component.isStreaming).toBeFalse();
      expect(component.isLoading).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. Message array management
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Message array management', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should preserve message order: user then assistant', () => {
      spies.kClawServiceSpy.chat.and.returnValue(of(mockChatResponse({ response: 'Hi there!' })));
      component.chatForm.patchValue({ message: 'Hello', stream: false });

      component.sendMessage();

      expect(component.messages[0].role).toBe('user');
      expect(component.messages[1].role).toBe('assistant');
    });

    it('should accumulate messages across multiple turns', () => {
      spies.kClawServiceSpy.chat.and.returnValue(of(mockChatResponse()));
      component.chatForm.patchValue({ stream: false });

      component.chatForm.patchValue({ message: 'Turn 1' });
      component.sendMessage();

      component.chatForm.patchValue({ message: 'Turn 2' });
      component.sendMessage();

      // 2 users + 2 assistants = 4
      expect(component.messages.length).toBe(4);
    });

    it('should assign ids to messages', () => {
      spies.kClawServiceSpy.chat.and.returnValue(of(mockChatResponse()));
      component.chatForm.patchValue({ message: 'Msg1', stream: false });
      component.sendMessage();

      component.chatForm.patchValue({ message: 'Msg2' });
      component.sendMessage();

      // All messages should have an id
      expect(component.messages.length).toBeGreaterThanOrEqual(2);
      component.messages.forEach(m => {
        expect(m.id).toBeTruthy();
      });
    });

    it('newSession should reset messages to empty array', () => {
      spies.kClawServiceSpy.chat.and.returnValue(of(mockChatResponse()));
      component.chatForm.patchValue({ message: 'Hello', stream: false });
      component.sendMessage();

      expect(component.messages.length).toBeGreaterThan(0);

      component.newSession();

      expect(component.messages).toEqual([]);
    });

    it('streaming placeholder id starts with "stream-"', () => {
      const streamSubject = new Subject<string>();
      spies.kClawServiceSpy.chatStream.and.returnValue(streamSubject.asObservable());
      component.chatForm.patchValue({ message: 'Go', stream: true });
      component.sendMessage();

      const placeholder = component.messages.find(m => m.isStreaming);
      expect(placeholder!.id).toMatch(/^stream-/);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. Form validation edge cases
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Form validation edge cases', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('chatForm should be invalid when message is empty string', () => {
      component.chatForm.patchValue({ message: '' });
      expect(component.chatForm.invalid).toBeTrue();
    });

    it('chatForm should be valid when message has content', () => {
      component.chatForm.patchValue({ message: 'Non-empty' });
      expect(component.chatForm.valid).toBeTrue();
    });

    it('should respect stream toggle from form value', () => {
      spies.kClawServiceSpy.chat.and.returnValue(of(mockChatResponse()));
      component.chatForm.patchValue({ message: 'Hello', stream: false });

      component.sendMessage();

      expect(spies.kClawServiceSpy.chat).toHaveBeenCalled();
      expect(spies.kClawServiceSpy.chatStream).not.toHaveBeenCalled();
    });

    it('should use chatStream when stream=true', () => {
      spies.kClawServiceSpy.chatStream.and.returnValue(of(''));
      component.chatForm.patchValue({ message: 'Hello', stream: true });

      component.sendMessage();

      expect(spies.kClawServiceSpy.chatStream).toHaveBeenCalled();
      expect(spies.kClawServiceSpy.chat).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. End-to-end conversation simulation
  // ─────────────────────────────────────────────────────────────────────────────

  describe('End-to-end conversation simulation', () => {
    beforeEach(() => {
      const jarvis = mockAgentDef({ name: 'jarvis', isDefault: true });
      const specialist = mockAgentDef({ name: 'specialist', isDefault: false });
      spies.agentsSubject.next([jarvis, specialist]);
      fixture.detectChanges();
    });

    it('should simulate a full sync conversation', fakeAsync(() => {
      const response = mockChatResponse({
        response: 'RAG stands for Retrieval Augmented Generation.',
        tokenUsage: { inputTokens: 80, outputTokens: 40 }
      });
      spies.kClawServiceSpy.chat.and.returnValue(of(response));
      component.chatForm.patchValue({ message: 'What is RAG?', stream: false });

      component.sendMessage();
      tick();

      expect(component.messages.length).toBe(2);
      expect(component.messages[0].role).toBe('user');
      expect(component.messages[0].content).toBe('What is RAG?');
      expect(component.messages[1].role).toBe('assistant');
      expect(component.messages[1].content).toContain('Retrieval Augmented Generation');
      expect(component.messages[1].tokenUsage!.inputTokens).toBe(80);
      expect(component.isLoading).toBeFalse();
    }));

    it('should simulate streaming conversation with chunked response', fakeAsync(() => {
      const streamSubject = new Subject<string>();
      spies.kClawServiceSpy.chatStream.and.returnValue(streamSubject.asObservable());
      component.chatForm.patchValue({ message: 'Explain RAG step by step', stream: true });

      component.sendMessage();
      tick();

      expect(component.isStreaming).toBeTrue();
      expect(component.messages.length).toBe(2); // user + placeholder

      // Simulate streaming chunks
      streamSubject.next('Step 1: ');
      streamSubject.next('Retrieve documents. ');
      streamSubject.next('Step 2: ');
      streamSubject.next('Augment the prompt.');
      tick();

      const assistantMsg = component.messages.find(m => m.role === 'assistant');
      expect(assistantMsg!.content).toBe(
        'Step 1: Retrieve documents. Step 2: Augment the prompt.'
      );

      // Complete the stream
      streamSubject.complete();
      tick();

      expect(component.isStreaming).toBeFalse();
      expect(component.isLoading).toBeFalse();
      expect(assistantMsg!.isStreaming).toBeFalse();
    }));

    it('should allow switching agents between turns', fakeAsync(() => {
      spies.kClawServiceSpy.chat.and.returnValue(of(mockChatResponse()));
      component.chatForm.patchValue({ stream: false });

      // Turn 1 with jarvis
      component.chatForm.patchValue({ message: 'Hello from jarvis', agentId: 'jarvis' });
      component.sendMessage();
      tick();

      // Switch agent
      const specialist = component.agents.find(a => a.name === 'specialist')!;
      component.selectAgent(specialist);
      expect(component.chatForm.value.agentId).toBe('specialist');

      // Turn 2 with specialist
      component.chatForm.patchValue({ message: 'Hello from specialist' });
      component.sendMessage();
      tick();

      const calls = spies.kClawServiceSpy.chat.calls.allArgs();
      expect(calls[0][0].agentId).toBe('jarvis');
      expect(calls[1][0].agentId).toBe('specialist');
    }));

    it('should start a new session and carry new sessionKey in next request', fakeAsync(() => {
      spies.kClawServiceSpy.chat.and.returnValue(of(mockChatResponse()));
      component.chatForm.patchValue({ stream: false });

      // First session request
      component.chatForm.patchValue({ message: 'Old session msg' });
      component.sendMessage();
      const firstSessionKey = component.sessionKey;
      tick();

      component.newSession();
      const newSessionKey = component.sessionKey;
      expect(newSessionKey).not.toBe(firstSessionKey);

      // Next request should use new session key
      component.chatForm.patchValue({ message: 'New session msg' });
      component.sendMessage();
      tick();

      const secondCallArg = spies.kClawServiceSpy.chat.calls.mostRecent().args[0];
      expect(secondCallArg.sessionKey).toBe(newSessionKey);
    }));
  });
});
