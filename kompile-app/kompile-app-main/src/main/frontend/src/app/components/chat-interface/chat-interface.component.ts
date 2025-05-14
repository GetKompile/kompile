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

import { Component } from '@angular/core';
import { RagService } from '../../services/rag.service';
import { RagQuery, RagResponse } from '../../models/api-models';
import { HttpErrorResponse } from '@angular/common/http';

interface ChatMessage {
  sender: 'user' | 'bot';
  text: string;
  error?: boolean;
}

@Component({
  standalone: false,
  selector: 'app-chat-interface',
  templateUrl: './chat-interface.component.html',
  styleUrls: ['./chat-interface.component.css']
})
export class ChatInterfaceComponent {
  userInput: string = '';
  conversation: ChatMessage[] = [];
  isLoading: boolean = false;
  useToolCalling: boolean = false; // "Configure model" aspect

  constructor(private ragService: RagService) { }

  sendMessage(): void {
    if (!this.userInput.trim()) {
      return;
    }

    const userQuery: RagQuery = {
      query: this.userInput,
      useToolCalling: this.useToolCalling
    };

    this.conversation.push({ sender: 'user', text: this.userInput });
    this.isLoading = true;
    this.userInput = ''; // Clear input

    this.ragService.queryRag(userQuery).subscribe({
      next: (response: RagResponse) => {
        if (response.answer) {
          this.conversation.push({ sender: 'bot', text: response.answer });
        } else if (response.error) {
          this.conversation.push({ sender: 'bot', text: `Error: ${response.error}`, error: true });
        } else {
          this.conversation.push({ sender: 'bot', text: 'Received an empty response.', error: true });
        }
        this.isLoading = false;
      },
      error: (err: HttpErrorResponse) => {
        this.conversation.push({ sender: 'bot', text: `Failed to get response: ${err.message}`, error: true });
        this.isLoading = false;
      }
    });
  }
}
