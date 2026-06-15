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

import { Component, OnInit, OnDestroy, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { Subscription } from 'rxjs';
import { PassthroughChatService } from '../../services/passthrough-chat.service';
import { AgentService } from '../../services/agent.service';
import { AgentProvider, PassthroughMessage } from '../../models/api-models';

@Component({
  selector: 'app-passthrough-chat',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatSlideToggleModule
  ],
  templateUrl: './passthrough-chat.component.html',
  styleUrls: ['./passthrough-chat.component.css']
})
export class PassthroughChatComponent implements OnInit, OnDestroy {

  @ViewChild('messageArea') messageArea!: ElementRef;
  @ViewChild('inputArea') inputArea!: ElementRef;

  agents: AgentProvider[] = [];
  selectedAgent: string = '';
  workingDirectory: string = '';
  skipPermissions: boolean = true;
  injectMcpTools: boolean = true;

  connected = false;
  agentReady = false;
  messages: PassthroughMessage[] = [];
  streamingContent = '';
  userInput = '';
  errorMessage = '';

  private subs: Subscription[] = [];

  constructor(
    private passthroughService: PassthroughChatService,
    private agentService: AgentService
  ) {}

  ngOnInit(): void {
    this.loadAgents();

    this.subs.push(
      this.passthroughService.connected$.subscribe(c => this.connected = c),
      this.passthroughService.agentReady$.subscribe(r => this.agentReady = r),
      this.passthroughService.messages$.subscribe(m => {
        this.messages = m;
        this.scrollToBottom();
      }),
      this.passthroughService.streamingContent$.subscribe(s => {
        this.streamingContent = s;
        this.scrollToBottom();
      }),
      this.passthroughService.error$.subscribe(e => this.errorMessage = e)
    );
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
    if (this.connected) {
      this.passthroughService.disconnect();
    }
  }

  loadAgents(): void {
    this.agentService.getAllAgents().subscribe(agents => {
      // Only show available CLI agents (passthrough doesn't support API agents)
      this.agents = agents.filter(a => a.available && a.agentType !== 'API');
      if (this.agents.length > 0 && !this.selectedAgent) {
        this.selectedAgent = this.agents[0].name;
      }
    });
  }

  connect(): void {
    if (!this.selectedAgent) return;
    this.errorMessage = '';
    this.passthroughService.clearMessages();
    this.passthroughService.connect({
      agentName: this.selectedAgent,
      skipPermissions: this.skipPermissions,
      workingDirectory: this.workingDirectory || undefined,
      injectMcpTools: this.injectMcpTools
    });
  }

  disconnect(): void {
    this.passthroughService.disconnect();
  }

  sendMessage(): void {
    const msg = this.userInput.trim();
    if (!msg || !this.agentReady) return;
    this.errorMessage = '';
    this.passthroughService.sendMessage(msg);
    this.userInput = '';
  }

  onKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      if (this.messageArea?.nativeElement) {
        const el = this.messageArea.nativeElement;
        el.scrollTop = el.scrollHeight;
      }
    }, 50);
  }
}
