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

import { Injectable } from '@angular/core';
import { SafeHtml } from '@angular/platform-browser';
import { marked, Renderer, Tokens } from 'marked';
import hljs from 'highlight.js';

/**
 * Represents a parsed segment of a message — either text content (which may contain markdown),
 * a thinking/reasoning block, or a tool use block.
 */
export interface MessageSegment {
  type: 'text' | 'thinking' | 'tool_use';
  content: string;
  /** For thinking blocks: whether the thinking is still in progress */
  isStreaming?: boolean;
  /** For tool_use blocks */
  toolName?: string;
  toolInput?: string;
  toolResult?: string;
  /**
   * Pre-rendered, sanitized HTML for text/thinking segments. Populated once by
   * the chat component so the expensive marked + highlight.js render does not
   * run on every Angular change-detection pass.
   */
  renderedContent?: SafeHtml;
}

/**
 * Service that handles markdown rendering with syntax highlighting,
 * thinking block extraction, and tool use formatting.
 */
@Injectable({ providedIn: 'root' })
export class MarkdownRendererService {

  private markedInstance: typeof marked;

  /**
   * Languages considered during auto-detection for fenced code blocks that omit
   * a language hint. highlightAuto() with no subset scans every registered
   * grammar (~190), which dominates render time for transcripts full of
   * unlabeled code blocks; restricting it to the languages that actually appear
   * in chat keeps detection fast while preserving useful highlighting.
   */
  private static readonly AUTO_DETECT_LANGUAGES = [
    'typescript', 'javascript', 'python', 'java', 'json', 'bash', 'shell',
    'xml', 'html', 'yaml', 'sql', 'css', 'markdown', 'c', 'cpp', 'csharp',
    'go', 'rust', 'kotlin', 'dockerfile', 'ini', 'diff', 'plaintext'
  ];

  constructor() {
    this.markedInstance = marked;
    this.configureMarked();
  }

  private configureMarked(): void {
    const renderer = new Renderer();

    // Custom code block renderer with language label and copy button
    renderer.code = ({ text, lang }: Tokens.Code): string => {
      const language = lang && hljs.getLanguage(lang) ? lang : '';
      const displayLang = language || 'text';
      let highlighted: string;

      if (language) {
        try {
          highlighted = hljs.highlight(text, { language }).value;
        } catch {
          highlighted = this.escapeHtml(text);
        }
      } else {
        try {
          highlighted = hljs.highlightAuto(text, MarkdownRendererService.AUTO_DETECT_LANGUAGES).value;
        } catch {
          highlighted = this.escapeHtml(text);
        }
      }

      return `<div class="code-block-wrapper">
        <div class="code-block-header">
          <span class="code-lang-label">${this.escapeHtml(displayLang)}</span>
          <button class="code-copy-btn" onclick="navigator.clipboard.writeText(decodeURIComponent(this.getAttribute('data-code'))).then(()=>{this.textContent='Copied!';setTimeout(()=>this.textContent='Copy',2000)})" data-code="${encodeURIComponent(text)}">Copy</button>
        </div>
        <pre class="code-block"><code class="hljs language-${this.escapeHtml(displayLang)}">${highlighted}</code></pre>
      </div>`;
    };

    // Inline code
    renderer.codespan = ({ text }: Tokens.Codespan): string => {
      return `<code class="inline-code">${this.escapeHtml(text)}</code>`;
    };

    // Tables
    renderer.table = ({ header, rows }: Tokens.Table): string => {
      const headerCells = header.map((cell: any) =>
        `<th>${this.markedInstance.parseInline(cell.text)}</th>`
      ).join('');

      const bodyRows = rows.map((row: any) => {
        const cells = row.map((cell: any) =>
          `<td>${this.markedInstance.parseInline(cell.text)}</td>`
        ).join('');
        return `<tr>${cells}</tr>`;
      }).join('');

      return `<div class="table-wrapper"><table class="md-table">
        <thead><tr>${headerCells}</tr></thead>
        <tbody>${bodyRows}</tbody>
      </table></div>`;
    };

    // Blockquote
    renderer.blockquote = ({ text }: Tokens.Blockquote): string => {
      return `<blockquote class="md-blockquote">${text}</blockquote>`;
    };

    // Links open in new tab
    renderer.link = ({ href, title, text }: Tokens.Link): string => {
      const titleAttr = title ? ` title="${this.escapeHtml(title)}"` : '';
      return `<a href="${this.escapeHtml(href)}" target="_blank" rel="noopener noreferrer" class="md-link"${titleAttr}>${text}</a>`;
    };

    this.markedInstance.setOptions({
      renderer,
      breaks: true,
      gfm: true,
    });
  }

  /**
   * Parse a raw message string into segments: text, thinking blocks, tool_use blocks.
   * Handles:
   *   <thinking>...</thinking> — thinking/reasoning blocks
   *   [tool:Name]...input...[/tool] — tool call blocks (from CLI transcript parsing)
   *   [tool-result]...output...[/tool-result] — tool result blocks
   */
  parseMessageSegments(content: string, isStreaming: boolean = false): MessageSegment[] {
    if (!content) return [];

    // Combined pattern matching all structured block types in order of appearance.
    // Each block is extracted and the text between/around them becomes text segments.
    const blockPattern = /(<thinking>[\s\S]*?<\/thinking>)|(\[tool:([^\]]+)\]\n?([\s\S]*?)\n?\[\/tool\])|(\[tool-result\]\n?([\s\S]*?)\n?\[\/tool-result\])/g;
    // Pattern for unclosed thinking block (streaming)
    const unclosedThinkingPattern = /<thinking>([\s\S]*)$/;

    const segments: MessageSegment[] = [];
    let lastIndex = 0;
    let match: RegExpExecArray | null;

    blockPattern.lastIndex = 0;

    while ((match = blockPattern.exec(content)) !== null) {
      // Add text before this block
      if (match.index > lastIndex) {
        const textBefore = content.substring(lastIndex, match.index).trim();
        if (textBefore) {
          segments.push({ type: 'text', content: textBefore });
        }
      }

      if (match[1]) {
        // <thinking>...</thinking>
        const inner = match[1].replace(/<\/?thinking>/g, '').trim();
        segments.push({ type: 'thinking', content: inner, isStreaming: false });
      } else if (match[2]) {
        // [tool:Name]...input...[/tool]
        const toolName = match[3];
        const toolInput = (match[4] || '').trim();
        segments.push({
          type: 'tool_use',
          content: '',
          toolName,
          toolInput: toolInput || undefined
        });
      } else if (match[5]) {
        // [tool-result]...output...[/tool-result]
        const toolResult = (match[6] || '').trim();
        segments.push({
          type: 'tool_use',
          content: '',
          toolName: 'Result',
          toolResult: toolResult || undefined
        });
      }

      lastIndex = match.index + match[0].length;
    }

    // Check for remaining content after last match
    const afterMatches = content.substring(lastIndex);

    if (afterMatches) {
      // Check for unclosed thinking block (during streaming)
      const unclosed = unclosedThinkingPattern.exec(afterMatches);
      if (unclosed && isStreaming) {
        const textBefore = afterMatches.substring(0, unclosed.index).trim();
        if (textBefore) {
          segments.push({ type: 'text', content: textBefore });
        }
        segments.push({
          type: 'thinking',
          content: unclosed[1].trim(),
          isStreaming: true
        });
      } else {
        const text = afterMatches.trim();
        if (text) {
          segments.push({ type: 'text', content: text });
        }
      }
    }

    // If no segments were created, treat entire content as text
    if (segments.length === 0 && content.trim()) {
      segments.push({ type: 'text', content: content });
    }

    return segments;
  }

  /**
   * Render a markdown string to sanitized HTML.
   */
  renderMarkdown(text: string): string {
    if (!text) return '';

    try {
      const result = this.markedInstance.parse(text);
      // marked.parse can return string or Promise<string>; we only use sync mode
      if (typeof result === 'string') {
        return result;
      }
      return this.escapeHtml(text);
    } catch (e) {
      // Fallback: escape and wrap in <p>
      return `<p>${this.escapeHtml(text)}</p>`;
    }
  }

  /**
   * Render inline markdown (no block-level elements).
   */
  renderInline(text: string): string {
    if (!text) return '';
    try {
      const result = this.markedInstance.parseInline(text);
      if (typeof result === 'string') {
        return result;
      }
      return this.escapeHtml(text);
    } catch {
      return this.escapeHtml(text);
    }
  }

  /**
   * Escape HTML special characters.
   */
  escapeHtml(text: string): string {
    const div = document.createElement('div');
    div.appendChild(document.createTextNode(text));
    return div.innerHTML;
  }
}
