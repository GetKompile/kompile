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

import {
  Component,
  Input,
  OnChanges,
  SimpleChanges,
  ChangeDetectionStrategy,
  ViewEncapsulation
} from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { CommonModule } from '@angular/common';
import { MarkdownRendererService, MessageSegment } from '../../services/markdown-renderer.service';

export interface ToolUseEvent {
  tool_name?: string;
  name?: string;
  input?: any;
  result?: string;
  status?: string;
}

@Component({
  selector: 'app-markdown-renderer',
  standalone: true,
  imports: [CommonModule],
  template: `
    <!-- Segments: thinking blocks, text, tool use -->
    <ng-container *ngFor="let segment of segments; let i = index">

      <!-- Thinking Block -->
      <div *ngIf="segment.type === 'thinking'" class="thinking-block"
           [class.streaming]="segment.isStreaming"
           [class.collapsed]="collapsedThinking[i]">
        <div class="thinking-header" (click)="toggleThinking(i)">
          <span class="thinking-icon" [class.spinning]="segment.isStreaming">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
              <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.5" stroke-dasharray="4 2" />
              <circle cx="8" cy="8" r="2" fill="currentColor" />
            </svg>
          </span>
          <span class="thinking-label">
            {{ segment.isStreaming ? 'Thinking...' : 'Thought process' }}
          </span>
          <span class="thinking-toggle">{{ collapsedThinking[i] ? '+' : '-' }}</span>
        </div>
        <div class="thinking-content" *ngIf="!collapsedThinking[i]"
             [innerHTML]="renderedThinking[i]">
        </div>
      </div>

      <!-- Tool Use Block -->
      <div *ngIf="segment.type === 'tool_use'" class="tool-use-block"
           [class.collapsed]="collapsedTools[i]">
        <div class="tool-use-header" (click)="toggleTool(i)">
          <span class="tool-icon">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M8.5 1.5L12.5 5.5L5.5 12.5H1.5V8.5L8.5 1.5Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
            </svg>
          </span>
          <span class="tool-name">{{ segment.toolName || 'Tool' }}</span>
          <span class="tool-toggle">{{ collapsedTools[i] ? '+' : '-' }}</span>
        </div>
        <div class="tool-use-content" *ngIf="!collapsedTools[i]">
          <div class="tool-input" *ngIf="segment.toolInput">
            <span class="tool-section-label">Input</span>
            <pre class="tool-json">{{ segment.toolInput }}</pre>
          </div>
          <div class="tool-result" *ngIf="segment.toolResult">
            <span class="tool-section-label">Result</span>
            <pre class="tool-json">{{ segment.toolResult }}</pre>
          </div>
        </div>
      </div>

      <!-- Regular Text (rendered as markdown) -->
      <div *ngIf="segment.type === 'text'" class="markdown-body"
           [innerHTML]="renderedSegments[i]">
      </div>

    </ng-container>

    <!-- Streaming cursor -->
    <span class="streaming-cursor" *ngIf="isStreaming">&#9612;</span>

    <!-- Tool use events from SSE stream -->
    <ng-container *ngIf="toolUses && toolUses.length > 0">
      <div *ngFor="let tool of toolUses; let j = index" class="tool-use-block"
           [class.collapsed]="collapsedExternalTools[j]">
        <div class="tool-use-header" (click)="toggleExternalTool(j)">
          <span class="tool-icon">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M8.5 1.5L12.5 5.5L5.5 12.5H1.5V8.5L8.5 1.5Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
            </svg>
          </span>
          <span class="tool-name">{{ tool.tool_name || tool.name || 'Tool' }}</span>
          <span class="tool-status" *ngIf="tool.status">{{ tool.status }}</span>
          <span class="tool-toggle">{{ collapsedExternalTools[j] ? '+' : '-' }}</span>
        </div>
        <div class="tool-use-content" *ngIf="!collapsedExternalTools[j]">
          <div class="tool-input" *ngIf="tool.input">
            <span class="tool-section-label">Input</span>
            <pre class="tool-json">{{ formatJson(tool.input) }}</pre>
          </div>
          <div class="tool-result" *ngIf="tool.result">
            <span class="tool-section-label">Result</span>
            <pre class="tool-json">{{ tool.result }}</pre>
          </div>
        </div>
      </div>
    </ng-container>
  `,
  styleUrls: ['./markdown-renderer.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None
})
export class MarkdownRendererComponent implements OnChanges {
  /** Raw message content (may contain markdown, thinking tags, etc.) */
  @Input() content: string = '';

  /** Whether this message is currently streaming */
  @Input() isStreaming: boolean = false;

  /** Tool use events from the SSE stream (separate from inline tool blocks) */
  @Input() toolUses: ToolUseEvent[] = [];

  /** Message role - affects rendering style */
  @Input() role: 'user' | 'assistant' | 'system' = 'assistant';

  /** Optional source references for inline [n] linking */
  @Input() sources: any[] = [];

  /** Parsed segments */
  segments: MessageSegment[] = [];

  /** Pre-rendered HTML for each segment */
  renderedSegments: SafeHtml[] = [];
  renderedThinking: SafeHtml[] = [];

  /** Collapse state */
  collapsedThinking: boolean[] = [];
  collapsedTools: boolean[] = [];
  collapsedExternalTools: boolean[] = [];

  constructor(
    private mdService: MarkdownRendererService,
    private sanitizer: DomSanitizer
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['content'] || changes['isStreaming'] || changes['toolUses'] || changes['sources']) {
      this.parseAndRender();
    }
  }

  private parseAndRender(): void {
    // For user messages, render as simple text (no complex markdown)
    if (this.role === 'user') {
      this.segments = [{ type: 'text', content: this.content }];
      this.renderedSegments = [
        this.sanitizer.bypassSecurityTrustHtml(
          `<p>${this.mdService.escapeHtml(this.content).replace(/\n/g, '<br>')}</p>`
        )
      ];
      this.renderedThinking = [];
      return;
    }

    // Parse into segments (thinking blocks, text, etc.)
    this.segments = this.mdService.parseMessageSegments(this.content, this.isStreaming);

    // Initialize collapse arrays if needed (preserve existing state)
    while (this.collapsedThinking.length < this.segments.length) {
      // Auto-collapse completed thinking blocks, expand streaming ones
      this.collapsedThinking.push(false);
    }
    while (this.collapsedTools.length < this.segments.length) {
      this.collapsedTools.push(true);
    }

    // Render each segment
    this.renderedSegments = this.segments.map((seg, i) => {
      if (seg.type === 'text') {
        let html = this.mdService.renderMarkdown(seg.content);
        // Post-process to add clickable source reference links
        if (this.sources && this.sources.length > 0) {
          html = this.addSourceRefLinks(html);
        }
        return this.sanitizer.bypassSecurityTrustHtml(html);
      }
      return this.sanitizer.bypassSecurityTrustHtml('');
    });

    this.renderedThinking = this.segments.map((seg, i) => {
      if (seg.type === 'thinking') {
        const html = this.mdService.renderMarkdown(seg.content);
        return this.sanitizer.bypassSecurityTrustHtml(html);
      }
      return this.sanitizer.bypassSecurityTrustHtml('');
    });

    // External tools collapse state
    if (this.toolUses) {
      while (this.collapsedExternalTools.length < this.toolUses.length) {
        this.collapsedExternalTools.push(true);
      }
    }
  }

  toggleThinking(index: number): void {
    this.collapsedThinking[index] = !this.collapsedThinking[index];
  }

  toggleTool(index: number): void {
    this.collapsedTools[index] = !this.collapsedTools[index];
  }

  toggleExternalTool(index: number): void {
    this.collapsedExternalTools[index] = !this.collapsedExternalTools[index];
  }

  /**
   * Replace [n] patterns in rendered HTML with clickable source reference spans.
   * Only replaces references that have a corresponding source (1-indexed).
   */
  private addSourceRefLinks(html: string): string {
    return html.replace(/\[(\d+)\]/g, (match, num) => {
      const index = parseInt(num, 10) - 1;
      if (index >= 0 && index < this.sources.length) {
        const source = this.sources[index];
        const tooltip = source?.sourceName || `Source ${index + 1}`;
        return `<span class="source-ref-link" data-source-index="${index}" title="${this.mdService.escapeHtml(tooltip)}">[${num}]</span>`;
      }
      return match;
    });
  }

  formatJson(input: any): string {
    if (typeof input === 'string') {
      try {
        return JSON.stringify(JSON.parse(input), null, 2);
      } catch {
        return input;
      }
    }
    try {
      return JSON.stringify(input, null, 2);
    } catch {
      return String(input);
    }
  }
}
