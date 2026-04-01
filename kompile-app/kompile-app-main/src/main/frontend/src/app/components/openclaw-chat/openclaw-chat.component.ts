/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, OnInit, OnDestroy } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Subject, takeUntil } from 'rxjs';
import { OpenClawService } from '../../services/openclaw.service';
import { AgentDefinition, OpenClawChatResponse } from '../../models/openclaw-models';

@Component({
  selector: 'app-openclaw-chat',
  standalone: false,
  templateUrl: './openclaw-chat.component.html',
  styleUrls: ['./openclaw-chat.component.css']
})
export class OpenClawChatComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  agents: AgentDefinition[] = [];
  selectedAgent: AgentDefinition | null = null;
  sessionKey: string = '';
  
  messages: ChatMessage[] = [];
  userInput: string = '';
  isLoading: boolean = false;
  isStreaming: boolean = false;
  
  chatForm: FormGroup;

  constructor(
    private fb: FormBuilder,
    private openClawService: OpenClawService
  ) {
    this.chatForm = this.fb.group({
      message: ['', Validators.required],
      agentId: ['jarvis'],
      stream: [true]
    });
  }

  ngOnInit(): void {
    this.openClawService.agents$
      .pipe(takeUntil(this.destroy$))
      .subscribe(agents => {
        this.agents = agents;
        if (agents.length > 0 && !this.selectedAgent) {
          this.selectedAgent = agents.find(a => a.isDefault) || agents[0];
        }
      });
    
    this.generateSessionKey();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private generateSessionKey(): void {
    this.sessionKey = 'session:' + Date.now().toString(36) + Math.random().toString(36).substr(2, 5);
  }

  sendMessage(): void {
    if (this.chatForm.invalid || this.isLoading) return;
    
    const message = this.chatForm.value.message.trim();
    const agentId = this.chatForm.value.agentId;
    const stream = this.chatForm.value.stream;
    
    this.addUserMessage(message);
    this.chatForm.patchValue({ message: '' });
    this.isLoading = true;
    
    if (stream) {
      this.sendStreamingMessage(message, agentId);
    } else {
      this.sendSyncMessage(message, agentId);
    }
  }

  private sendSyncMessage(message: string, agentId: string): void {
    this.openClawService.chat({
      agentId,
      sessionKey: this.sessionKey,
      message,
      stream: false
    }).subscribe({
      next: (response) => {
        this.addAssistantMessage(response.response, response.tokenUsage);
        this.isLoading = false;
      },
      error: (error) => {
        this.addErrorMessage('Failed to get response: ' + error.message);
        this.isLoading = false;
      }
    });
  }

  private sendStreamingMessage(message: string, agentId: string): void {
    this.isStreaming = true;
    const placeholderId = this.addStreamingPlaceholder();
    
    this.openClawService.chatStream({
      agentId,
      sessionKey: this.sessionKey,
      message,
      stream: true
    }).subscribe({
      next: (chunk: string) => {
        this.updateStreamingMessage(placeholderId, chunk);
      },
      complete: () => {
        this.isStreaming = false;
        this.isLoading = false;
        this.finalizeStreamingMessage(placeholderId);
      },
      error: (error: Error) => {
        this.isStreaming = false;
        this.isLoading = false;
        this.updateStreamingMessage(placeholderId, 'Error: ' + error.message);
      }
    });
  }

  private addUserMessage(content: string): void {
    this.messages.push({
      id: Date.now().toString(),
      role: 'user',
      content,
      timestamp: new Date()
    });
    this.scrollToBottom();
  }

  private addAssistantMessage(content: string, tokenUsage?: any): void {
    this.messages.push({
      id: Date.now().toString(),
      role: 'assistant',
      content,
      timestamp: new Date(),
      tokenUsage
    });
    this.scrollToBottom();
  }

  private addStreamingPlaceholder(): string {
    const id = 'stream-' + Date.now();
    this.messages.push({
      id,
      role: 'assistant',
      content: '',
      timestamp: new Date(),
      isStreaming: true
    });
    return id;
  }

  private updateStreamingMessage(id: string, chunk: string): void {
    const msg = this.messages.find(m => m.id === id);
    if (msg) {
      msg.content += chunk;
    }
    this.scrollToBottom();
  }

  private finalizeStreamingMessage(id: string): void {
    const msg = this.messages.find(m => m.id === id);
    if (msg) {
      msg.isStreaming = false;
    }
  }

  private addErrorMessage(content: string): void {
    this.messages.push({
      id: Date.now().toString(),
      role: 'error',
      content,
      timestamp: new Date()
    });
    this.scrollToBottom();
  }

  newSession(): void {
    this.messages = [];
    this.generateSessionKey();
  }

  selectAgent(agent: AgentDefinition): void {
    this.selectedAgent = agent;
    this.chatForm.patchValue({ agentId: agent.name });
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      const container = document.querySelector('.messages-container');
      if (container) {
        container.scrollTop = container.scrollHeight;
      }
    }, 50);
  }
}

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'error';
  content: string;
  timestamp: Date;
  isStreaming?: boolean;
  tokenUsage?: { inputTokens: number; outputTokens: number };
}