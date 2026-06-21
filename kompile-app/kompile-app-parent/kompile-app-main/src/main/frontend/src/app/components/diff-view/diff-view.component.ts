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

import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';

/** Parsed unified-diff line with metadata for rendering. */
interface DiffLine {
  text: string;
  type: 'add' | 'remove' | 'context' | 'header' | 'file';
  oldLineNo: number | null;
  newLineNo: number | null;
}

interface SplitPair {
  left: DiffLine | null;
  right: DiffLine | null;
}

/**
 * Shared, source-agnostic diff renderer. Feed it a `unifiedDiff` string (git
 * commit diffs, file-history, agent edits) or an `oldString`/`newString` pair;
 * it renders either a unified or a side-by-side (split) view. This is the single
 * place diffs are rendered across the git, agent, and compare browsers so the
 * three always look and behave identically.
 */
@Component({
  selector: 'app-diff-view',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './diff-view.component.html',
  styleUrls: ['./diff-view.component.css']
})
export class DiffViewComponent implements OnChanges {

  @Input() unifiedDiff: string | null = null;
  @Input() oldString: string | null = null;
  @Input() newString: string | null = null;
  @Input() filePath = '';
  @Input() viewMode: 'unified' | 'split' = 'split';
  @Input() showLineNumbers = true;
  /** Shown when there is nothing textual to render (e.g. binary or pure rename). */
  @Input() emptyMessage = 'No textual changes to display.';

  parsedDiff: DiffLine[] = [];
  splitPairs: SplitPair[] = [];

  ngOnChanges(): void {
    this.parsedDiff = this.parseDiffLines();
    this.splitPairs = this.computeSplitPairs();
  }

  get isEmpty(): boolean {
    return this.parsedDiff.every(l => l.type === 'file' || l.type === 'header');
  }

  private parseDiffLines(): DiffLine[] {
    const lines: DiffLine[] = [];

    if (this.unifiedDiff) {
      let oldLine = 0;
      let newLine = 0;

      for (const raw of this.unifiedDiff.split('\n')) {
        if (raw.startsWith('---') || raw.startsWith('+++')) {
          lines.push({ text: raw, type: 'file', oldLineNo: null, newLineNo: null });
        } else if (raw.startsWith('@@')) {
          const match = raw.match(/@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
          if (match) {
            oldLine = parseInt(match[1], 10) - 1;
            newLine = parseInt(match[2], 10) - 1;
          }
          lines.push({ text: raw, type: 'header', oldLineNo: null, newLineNo: null });
        } else if (raw.startsWith('+')) {
          newLine++;
          lines.push({ text: raw.substring(1), type: 'add', oldLineNo: null, newLineNo: newLine });
        } else if (raw.startsWith('-')) {
          oldLine++;
          lines.push({ text: raw.substring(1), type: 'remove', oldLineNo: oldLine, newLineNo: null });
        } else {
          oldLine++;
          newLine++;
          lines.push({ text: raw.startsWith(' ') ? raw.substring(1) : raw, type: 'context', oldLineNo: oldLine, newLineNo: newLine });
        }
      }
    } else if (this.oldString || this.newString) {
      // Build a synthetic diff from old/new strings.
      if (this.oldString) {
        lines.push({ text: '--- a/' + (this.filePath || 'file'), type: 'file', oldLineNo: null, newLineNo: null });
        let ln = 0;
        for (const line of this.oldString.split('\n')) {
          ln++;
          lines.push({ text: line, type: 'remove', oldLineNo: ln, newLineNo: null });
        }
      }
      if (this.newString) {
        lines.push({ text: '+++ b/' + (this.filePath || 'file'), type: 'file', oldLineNo: null, newLineNo: null });
        let ln = 0;
        for (const line of this.newString.split('\n')) {
          ln++;
          lines.push({ text: line, type: 'add', oldLineNo: null, newLineNo: ln });
        }
      }
    }

    return lines;
  }

  /** For split view: pair up consecutive remove/add lines side by side. */
  private computeSplitPairs(): SplitPair[] {
    const pairs: SplitPair[] = [];
    let i = 0;
    while (i < this.parsedDiff.length) {
      const line = this.parsedDiff[i];
      if (line.type === 'file' || line.type === 'header' || line.type === 'context') {
        pairs.push({ left: line, right: line });
        i++;
      } else if (line.type === 'remove') {
        const removes: DiffLine[] = [];
        while (i < this.parsedDiff.length && this.parsedDiff[i].type === 'remove') {
          removes.push(this.parsedDiff[i]);
          i++;
        }
        const adds: DiffLine[] = [];
        while (i < this.parsedDiff.length && this.parsedDiff[i].type === 'add') {
          adds.push(this.parsedDiff[i]);
          i++;
        }
        const maxLen = Math.max(removes.length, adds.length);
        for (let j = 0; j < maxLen; j++) {
          pairs.push({
            left: j < removes.length ? removes[j] : null,
            right: j < adds.length ? adds[j] : null
          });
        }
      } else if (line.type === 'add') {
        pairs.push({ left: null, right: line });
        i++;
      } else {
        i++;
      }
    }
    return pairs;
  }
}
