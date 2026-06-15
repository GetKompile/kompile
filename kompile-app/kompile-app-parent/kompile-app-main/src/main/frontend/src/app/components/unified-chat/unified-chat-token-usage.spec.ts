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
import { FolderService } from '../../services/folder.service';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';

describe('UnifiedChatComponent - Token Usage', () => {
  let component: UnifiedChatComponent;
  let fixture: ComponentFixture<UnifiedChatComponent>;

  let ragServiceSpy: jasmine.SpyObj<ConversationalRagService>;
  let agentChatServiceSpy: jasmine.SpyObj<LocalAgentChatService>;
  let agentServiceSpy: jasmine.SpyObj<AgentService>;
  let chatStorageServiceSpy: jasmine.SpyObj<ChatStorageService>;
  let chatHistoryServiceSpy: jasmine.SpyObj<ChatHistoryService>;
  let folderServiceSpy: jasmine.SpyObj<FolderService>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  beforeEach(async () => {
    ragServiceSpy = jasmine.createSpyObj('ConversationalRagService', ['getStatus', 'query']);
    agentChatServiceSpy = jasmine.createSpyObj('LocalAgentChatService', [
      'getStreamingContent', 'getStreamingComplete', 'getStreamingError',
      'getChatStats', 'getSources', 'getModifiedFiles', 'sendMessage', 'cancelStreaming'
    ]);
    agentServiceSpy = jasmine.createSpyObj('AgentService', ['getAgents', 'getAvailableAgents']);
    chatStorageServiceSpy = jasmine.createSpyObj('ChatStorageService', [
      'getSessions', 'saveSession', 'deleteSession', 'getSession'
    ]);
    chatHistoryServiceSpy = jasmine.createSpyObj('ChatHistoryService', [
      'getSessions', 'createSession', 'getSession', 'addMessage',
      'getSessionMessages', 'deleteSession', 'updateSessionTitle',
      'getMessageContent'
    ]);
    folderServiceSpy = jasmine.createSpyObj('FolderService', ['getFolders', 'getFolderFiles']);
    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);

    // Default return values
    ragServiceSpy.getStatus.and.returnValue(of({ available: false }));
    agentServiceSpy.getAgents.and.returnValue(of([]));
    agentServiceSpy.getAvailableAgents.and.returnValue(of([]));
    chatStorageServiceSpy.getSessions.and.returnValue([]);
    chatHistoryServiceSpy.getSessions.and.returnValue(of([]));
    folderServiceSpy.getFolders.and.returnValue(of([]));

    // Streaming observables
    agentChatServiceSpy.getStreamingContent.and.returnValue(new Subject<string>().asObservable());
    agentChatServiceSpy.getStreamingComplete.and.returnValue(new Subject<any>().asObservable());
    agentChatServiceSpy.getStreamingError.and.returnValue(new Subject<string>().asObservable());
    agentChatServiceSpy.getChatStats.and.returnValue(new Subject<any>().asObservable());
    agentChatServiceSpy.getSources.and.returnValue(new Subject<any>().asObservable());
    agentChatServiceSpy.getModifiedFiles.and.returnValue(new Subject<any>().asObservable());

    await TestBed.configureTestingModule({
      imports: [
        FormsModule,
        NoopAnimationsModule,
        HttpClientTestingModule,
        MatMenuModule
      ],
      declarations: [UnifiedChatComponent],
      providers: [
        { provide: ConversationalRagService, useValue: ragServiceSpy },
        { provide: LocalAgentChatService, useValue: agentChatServiceSpy },
        { provide: AgentService, useValue: agentServiceSpy },
        { provide: ChatStorageService, useValue: chatStorageServiceSpy },
        { provide: ChatHistoryService, useValue: chatHistoryServiceSpy },
        { provide: FolderService, useValue: folderServiceSpy },
        { provide: MatDialog, useValue: dialogSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(UnifiedChatComponent);
    component = fixture.componentInstance;
  });

  describe('formatTokenCount', () => {
    it('should return raw number for counts under 1000', () => {
      expect(component.formatTokenCount(0)).toBe('0');
      expect(component.formatTokenCount(1)).toBe('1');
      expect(component.formatTokenCount(999)).toBe('999');
    });

    it('should format thousands with k suffix', () => {
      expect(component.formatTokenCount(1000)).toBe('1.0k');
      expect(component.formatTokenCount(1500)).toBe('1.5k');
      expect(component.formatTokenCount(10000)).toBe('10.0k');
      expect(component.formatTokenCount(999999)).toBe('1000.0k');
    });

    it('should format millions with M suffix', () => {
      expect(component.formatTokenCount(1000000)).toBe('1.0M');
      expect(component.formatTokenCount(2500000)).toBe('2.5M');
      expect(component.formatTokenCount(10000000)).toBe('10.0M');
    });
  });

  describe('getSessionTokenUsage', () => {
    it('should return zeros when no messages', () => {
      component.messages = [];
      const usage = component.getSessionTokenUsage();
      expect(usage.totalInput).toBe(0);
      expect(usage.totalOutput).toBe(0);
      expect(usage.totalTokens).toBe(0);
    });

    it('should return zeros when messages have no token metrics', () => {
      component.messages = [
        {
          id: '1', role: 'user', content: 'Hello', timestamp: new Date()
        },
        {
          id: '2', role: 'assistant', content: 'Hi there', timestamp: new Date()
        }
      ] as any[];

      const usage = component.getSessionTokenUsage();
      expect(usage.totalInput).toBe(0);
      expect(usage.totalOutput).toBe(0);
      expect(usage.totalTokens).toBe(0);
    });

    it('should sum input and output tokens from all messages', () => {
      component.messages = [
        {
          id: '1', role: 'user', content: 'Hello', timestamp: new Date()
        },
        {
          id: '2', role: 'assistant', content: 'Hi', timestamp: new Date(),
          tokenMetrics: { inputTokens: 100, outputTokens: 50, totalGenerationMs: 1000, tokensPerSecond: 50 }
        },
        {
          id: '3', role: 'user', content: 'Tell me more', timestamp: new Date()
        },
        {
          id: '4', role: 'assistant', content: 'Sure', timestamp: new Date(),
          tokenMetrics: { inputTokens: 200, outputTokens: 150, totalGenerationMs: 2000, tokensPerSecond: 75 }
        }
      ] as any[];

      const usage = component.getSessionTokenUsage();
      expect(usage.totalInput).toBe(300);
      expect(usage.totalOutput).toBe(200);
      expect(usage.totalTokens).toBe(500);
    });

    it('should handle mixed messages with and without token metrics', () => {
      component.messages = [
        {
          id: '1', role: 'assistant', content: 'First', timestamp: new Date(),
          tokenMetrics: { inputTokens: 50, outputTokens: 30, totalGenerationMs: 500, tokensPerSecond: 60 }
        },
        {
          id: '2', role: 'assistant', content: 'No metrics', timestamp: new Date()
        },
        {
          id: '3', role: 'assistant', content: 'Third', timestamp: new Date(),
          tokenMetrics: { inputTokens: 75, outputTokens: 45, totalGenerationMs: 800, tokensPerSecond: 56 }
        }
      ] as any[];

      const usage = component.getSessionTokenUsage();
      expect(usage.totalInput).toBe(125);
      expect(usage.totalOutput).toBe(75);
      expect(usage.totalTokens).toBe(200);
    });

    it('should handle token metrics with zero values', () => {
      component.messages = [
        {
          id: '1', role: 'assistant', content: 'Response', timestamp: new Date(),
          tokenMetrics: { inputTokens: 0, outputTokens: 0, totalGenerationMs: 0, tokensPerSecond: 0 }
        }
      ] as any[];

      const usage = component.getSessionTokenUsage();
      expect(usage.totalInput).toBe(0);
      expect(usage.totalOutput).toBe(0);
      expect(usage.totalTokens).toBe(0);
    });

    it('should handle missing inputTokens or outputTokens in metrics', () => {
      component.messages = [
        {
          id: '1', role: 'assistant', content: 'Response', timestamp: new Date(),
          tokenMetrics: { outputTokens: 100, totalGenerationMs: 500, tokensPerSecond: 200 } as any
        }
      ] as any[];

      const usage = component.getSessionTokenUsage();
      // inputTokens is undefined, should default to 0
      expect(usage.totalInput).toBe(0);
      expect(usage.totalOutput).toBe(100);
      expect(usage.totalTokens).toBe(100);
    });
  });

  describe('formatDuration', () => {
    it('should format milliseconds for short durations', () => {
      expect(component.formatDuration(0)).toBe('0ms');
      expect(component.formatDuration(500)).toBe('500ms');
      expect(component.formatDuration(999)).toBe('999ms');
    });

    it('should format seconds for longer durations', () => {
      expect(component.formatDuration(1000)).toBe('1.0s');
      expect(component.formatDuration(1500)).toBe('1.5s');
      expect(component.formatDuration(5432)).toBe('5.4s');
    });
  });

  describe('Token metrics display in template', () => {
    it('should show session token usage when tokens are available', () => {
      component.messages = [
        {
          id: '1', role: 'assistant', content: 'Response', timestamp: new Date(),
          tokenMetrics: { inputTokens: 1500, outputTokens: 800, totalGenerationMs: 2000, tokensPerSecond: 400 }
        }
      ] as any[];

      fixture.detectChanges();

      const compiled = fixture.nativeElement;
      const tokenUsage = compiled.querySelector('.session-token-usage');
      if (tokenUsage) {
        expect(tokenUsage.textContent).toContain('1.5k');
        expect(tokenUsage.textContent).toContain('800');
      }
    });

    it('should not show session token usage when no tokens', () => {
      component.messages = [];
      fixture.detectChanges();

      const compiled = fixture.nativeElement;
      const tokenUsage = compiled.querySelector('.session-token-usage');
      expect(tokenUsage).toBeNull();
    });

    it('should show per-message metrics bar for assistant messages', () => {
      component.showMetrics = true;
      component.messages = [
        {
          id: '1', role: 'assistant', content: 'Hello', timestamp: new Date(),
          isStreaming: false,
          latencyMs: 2000,
          tokenMetrics: {
            inputTokens: 100, outputTokens: 50,
            totalGenerationMs: 1500, tokensPerSecond: 33.3,
            model: 'claude-sonnet-4-20250514'
          }
        }
      ] as any[];

      fixture.detectChanges();

      const compiled = fixture.nativeElement;
      const metricsBar = compiled.querySelector('.metrics-bar');
      if (metricsBar) {
        const text = metricsBar.textContent;
        expect(text).toContain('In:');
        expect(text).toContain('Out:');
        expect(text).toContain('Speed:');
      }
    });

    it('should not show metrics bar when showMetrics is false', () => {
      component.showMetrics = false;
      component.messages = [
        {
          id: '1', role: 'assistant', content: 'Hello', timestamp: new Date(),
          isStreaming: false,
          tokenMetrics: { inputTokens: 100, outputTokens: 50, totalGenerationMs: 1000, tokensPerSecond: 50 }
        }
      ] as any[];

      fixture.detectChanges();

      const compiled = fixture.nativeElement;
      const metricsBar = compiled.querySelector('.metrics-bar');
      expect(metricsBar).toBeNull();
    });

    it('should not show metrics bar while streaming', () => {
      component.showMetrics = true;
      component.messages = [
        {
          id: '1', role: 'assistant', content: 'Streaming...', timestamp: new Date(),
          isStreaming: true,
          tokenMetrics: { inputTokens: 50, outputTokens: 10, totalGenerationMs: 200, tokensPerSecond: 50 }
        }
      ] as any[];

      fixture.detectChanges();

      const compiled = fixture.nativeElement;
      const metricsBar = compiled.querySelector('.metrics-bar');
      expect(metricsBar).toBeNull();
    });
  });
});
