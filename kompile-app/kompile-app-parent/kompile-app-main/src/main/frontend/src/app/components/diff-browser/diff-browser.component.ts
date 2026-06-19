/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, OnInit, OnDestroy, OnChanges, SimpleChanges, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatMenuModule } from '@angular/material/menu';
import {
  DiffIndexService,
  DiffIndexEntry,
  DiffProject,
  DiffAgent,
  DiffIndexStats,
  DiffSearchParams
} from '../../services/diff-index.service';

/** Parsed unified-diff line with metadata for rendering. */
interface DiffLine {
  text: string;
  type: 'add' | 'remove' | 'context' | 'header' | 'file';
  oldLineNo: number | null;
  newLineNo: number | null;
}

/** A file-tree node: either a directory (has children) or a leaf (has entries). */
interface FileTreeNode {
  name: string;
  path: string;
  isDir: boolean;
  expanded: boolean;
  children: FileTreeNode[];
  entries: DiffIndexEntry[];
  totalDiffs: number;
}

@Component({
  selector: 'app-diff-browser',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatChipsModule, MatTooltipModule, MatProgressBarModule,
    MatDividerModule, MatButtonToggleModule, MatMenuModule
  ],
  templateUrl: './diff-browser.component.html',
  styleUrls: ['./diff-browser.component.css']
})
export class DiffBrowserComponent implements OnInit, OnDestroy, OnChanges {

  /** When set, scopes all diffs to these project directories (per-project mode). */
  @Input() projectDirectories: string[] = [];

  // ── Data ─────────────────────────────────────────────────────────
  allEntries: DiffIndexEntry[] = [];
  projects: DiffProject[] = [];
  agents: DiffAgent[] = [];
  stats: DiffIndexStats | null = null;

  // ── Selection state ──────────────────────────────────────────────
  selectedEntry: DiffIndexEntry | null = null;
  parsedDiff: DiffLine[] = [];
  selectedProject: string | null = null;
  selectedAgent: string | null = null;

  // ── File tree ────────────────────────────────────────────────────
  fileTree: FileTreeNode[] = [];

  // ── Filter / search ──────────────────────────────────────────────
  searchQuery = '';
  fileFilter = '';
  diffTypeFilter: string | null = null;

  // ── View options ─────────────────────────────────────────────────
  diffViewMode: 'unified' | 'split' = 'unified';
  showLineNumbers = true;

  // ── UI state ─────────────────────────────────────────────────────
  loading = false;
  reindexing = false;
  sidebarCollapsed = false;

  // ── Reindex progress ────────────────────────────────────────────
  reindexMessage: string | null = null;
  reindexError: string | null = null;
  reindexDone = false;
  reindexNewEntries = 0;
  reindexSessions = 0;
  reindexSources = 0;
  reindexErrorCount = 0;
  private pollTimer: ReturnType<typeof setInterval> | null = null;

  constructor(private diffService: DiffIndexService) {}

  /** True when project context is provided externally (hides project filter chips). */
  get externalProject(): boolean {
    return this.projectDirectories.length > 0;
  }

  ngOnInit(): void {
    this.loadAll();
    // Check if indexing was already in progress (e.g. started from another tab)
    this.diffService.getStats().subscribe({
      next: (s) => {
        if (s.indexing) {
          this.reindexing = true;
          this.reindexMessage = s.indexMessage || 'Indexing in progress...';
          this.startPolling();
        }
      }
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['projectDirectories'] && !changes['projectDirectories'].firstChange) {
      this.clearSelection();
      this.selectedProject = null;
      this.loadEntries();
    }
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  // ── Data loading ─────────────────────────────────────────────────

  loadAll(): void {
    this.loading = true;
    this.diffService.getStats().subscribe({
      next: s => this.stats = s,
      error: () => this.stats = null
    });
    this.diffService.listProjects().subscribe({
      next: p => this.projects = p,
      error: () => this.projects = []
    });
    this.diffService.listAgents().subscribe({
      next: a => this.agents = a,
      error: () => this.agents = []
    });
    this.loadEntries();
  }

  loadEntries(): void {
    this.loading = true;
    const params: DiffSearchParams = { limit: 500 };
    if (this.selectedAgent) params.agent = this.selectedAgent;
    if (this.searchQuery) params.contentQuery = this.searchQuery;
    if (this.fileFilter) params.filePath = this.fileFilter;

    // Use external project directory or manual selection
    if (this.externalProject) {
      // API supports one projectDirectory — use the first, client-filter the rest
      params.projectDirectory = this.projectDirectories[0];
    } else if (this.selectedProject) {
      params.projectDirectory = this.selectedProject;
    }

    this.diffService.search(params).subscribe({
      next: entries => {
        // If multiple external directories, client-filter to include all matches
        if (this.externalProject && this.projectDirectories.length > 1) {
          const dirs = this.projectDirectories;
          entries = entries.filter(e =>
            dirs.some(d => e.projectDirectory === d || e.filePath?.startsWith(d))
          );
        }
        this.allEntries = entries;
        this.buildFileTree();
        this.loading = false;
      },
      error: () => {
        this.allEntries = [];
        this.fileTree = [];
        this.loading = false;
      }
    });
  }

  // ── File tree construction ───────────────────────────────────────

  buildFileTree(): void {
    let entries = this.allEntries;

    if (this.diffTypeFilter) {
      entries = entries.filter(e => e.diffType === this.diffTypeFilter);
    }

    const root: Record<string, FileTreeNode> = {};

    for (const entry of entries) {
      const fp = entry.filePath || 'unknown';
      const parts = fp.split('/').filter(p => p.length > 0);

      let current = root;
      let pathSoFar = '';

      for (let i = 0; i < parts.length; i++) {
        pathSoFar += '/' + parts[i];
        const isLast = i === parts.length - 1;

        if (!current[parts[i]]) {
          current[parts[i]] = {
            name: parts[i],
            path: pathSoFar,
            isDir: !isLast,
            expanded: false,
            children: [],
            entries: [],
            totalDiffs: 0
          };
        }

        const node = current[parts[i]];
        node.totalDiffs++;

        if (isLast) {
          node.isDir = false;
          node.entries.push(entry);
        } else {
          node.isDir = true;
          // Convert children array to a lookup for building
          if (!node.children) node.children = [];
          const childMap: Record<string, FileTreeNode> = {};
          for (const c of node.children) {
            childMap[c.name] = c;
          }
          // Re-enter loop logic via next step
          if (!childMap[parts[i + 1]]) {
            const childNode: FileTreeNode = {
              name: parts[i + 1],
              path: pathSoFar + '/' + parts[i + 1],
              isDir: i + 1 < parts.length - 1,
              expanded: false,
              children: [],
              entries: [],
              totalDiffs: 0
            };
            node.children.push(childNode);
          }
        }
      }
    }

    // Simpler approach: group by directory
    this.fileTree = this.buildTreeFromEntries(entries);
  }

  private buildTreeFromEntries(entries: DiffIndexEntry[]): FileTreeNode[] {
    // Group entries by their directory path
    const dirMap = new Map<string, DiffIndexEntry[]>();

    for (const entry of entries) {
      const fp = entry.filePath || 'unknown';
      const lastSlash = fp.lastIndexOf('/');
      const dir = lastSlash > 0 ? fp.substring(0, lastSlash) : '/';
      if (!dirMap.has(dir)) dirMap.set(dir, []);
      dirMap.get(dir)!.push(entry);
    }

    // Sort directories, build nodes
    const nodes: FileTreeNode[] = [];
    const sortedDirs = Array.from(dirMap.keys()).sort();

    for (const dir of sortedDirs) {
      const dirEntries = dirMap.get(dir)!;
      // Group files within this directory
      const fileMap = new Map<string, DiffIndexEntry[]>();
      for (const e of dirEntries) {
        const name = this.getFileName(e.filePath);
        if (!fileMap.has(name)) fileMap.set(name, []);
        fileMap.get(name)!.push(e);
      }

      const fileNodes: FileTreeNode[] = Array.from(fileMap.entries()).map(([name, fEntries]) => ({
        name,
        path: fEntries[0].filePath,
        isDir: false,
        expanded: false,
        children: [],
        entries: fEntries.sort((a, b) => (b.timestamp || '').localeCompare(a.timestamp || '')),
        totalDiffs: fEntries.length
      }));

      nodes.push({
        name: this.shortenDir(dir),
        path: dir,
        isDir: true,
        expanded: nodes.length < 3, // auto-expand first few
        children: fileNodes.sort((a, b) => a.name.localeCompare(b.name)),
        entries: [],
        totalDiffs: dirEntries.length
      });
    }

    return nodes;
  }

  // ── Selection ────────────────────────────────────────────────────

  selectFileEntry(entry: DiffIndexEntry): void {
    this.loading = true;
    this.diffService.getEntry(entry.id).subscribe({
      next: full => {
        this.selectedEntry = full;
        this.parsedDiff = this.parseDiffLines(full);
        this.loading = false;
      },
      error: () => {
        this.selectedEntry = entry;
        this.parsedDiff = this.parseDiffLines(entry);
        this.loading = false;
      }
    });
  }

  clearSelection(): void {
    this.selectedEntry = null;
    this.parsedDiff = [];
  }

  toggleDir(node: FileTreeNode): void {
    node.expanded = !node.expanded;
  }

  // ── Filters ──────────────────────────────────────────────────────

  setProject(projectDir: string | null): void {
    this.selectedProject = projectDir;
    this.clearSelection();
    this.loadEntries();
  }

  setAgent(agent: string | null): void {
    this.selectedAgent = agent;
    this.clearSelection();
    this.loadEntries();
  }

  setDiffType(type: string | null): void {
    this.diffTypeFilter = type;
    this.buildFileTree();
  }

  applySearch(): void {
    this.clearSelection();
    this.loadEntries();
  }

  clearFilters(): void {
    if (!this.externalProject) {
      this.selectedProject = null;
    }
    this.selectedAgent = null;
    this.searchQuery = '';
    this.fileFilter = '';
    this.diffTypeFilter = null;
    this.clearSelection();
    this.loadEntries();
  }

  triggerReindex(): void {
    this.reindexing = true;
    this.reindexDone = false;
    this.reindexError = null;
    this.reindexMessage = 'Starting reindex...';
    this.reindexNewEntries = 0;
    this.reindexSessions = 0;
    this.reindexSources = 0;
    this.reindexErrorCount = 0;

    this.diffService.reindex().subscribe({
      next: () => this.startPolling(),
      error: () => {
        this.reindexing = false;
        this.reindexError = 'Failed to start reindex — server returned an error';
      }
    });
  }

  private startPolling(): void {
    this.stopPolling();
    this.pollTimer = setInterval(() => this.pollReindexStatus(), 1500);
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }

  private pollReindexStatus(): void {
    this.diffService.getStats().subscribe({
      next: (s) => {
        this.stats = s;
        this.reindexNewEntries = s.indexedCount || 0;
        this.reindexSessions = s.sessionsScanned || 0;
        this.reindexSources = s.sourcesScanned || 0;
        this.reindexErrorCount = s.indexErrors || 0;
        this.reindexMessage = s.indexMessage || 'Indexing...';

        if (s.indexError) {
          this.reindexError = s.indexError;
        }

        if (!s.indexing) {
          // Indexing finished
          this.stopPolling();
          this.reindexing = false;
          this.reindexDone = true;
          // Reload all data with fresh results
          this.loadAll();
          // Auto-dismiss success after 8 seconds
          setTimeout(() => { this.reindexDone = false; }, 8000);
        }
      },
      error: () => {
        this.stopPolling();
        this.reindexing = false;
        this.reindexError = 'Lost connection while polling reindex status';
      }
    });
  }

  dismissReindexStatus(): void {
    this.reindexDone = false;
    this.reindexError = null;
  }

  // ── Diff parsing ─────────────────────────────────────────────────

  parseDiffLines(entry: DiffIndexEntry): DiffLine[] {
    const lines: DiffLine[] = [];

    if (entry.unifiedDiff) {
      let oldLine = 0;
      let newLine = 0;

      for (const raw of entry.unifiedDiff.split('\n')) {
        if (raw.startsWith('---') || raw.startsWith('+++')) {
          lines.push({ text: raw, type: 'file', oldLineNo: null, newLineNo: null });
        } else if (raw.startsWith('@@')) {
          // Parse hunk header: @@ -oldStart,oldLen +newStart,newLen @@
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
    } else if (entry.oldString || entry.newString) {
      // Build a synthetic diff from old/new strings
      if (entry.oldString) {
        lines.push({ text: '--- a/' + (entry.filePath || 'file'), type: 'file', oldLineNo: null, newLineNo: null });
        let ln = 0;
        for (const line of entry.oldString.split('\n')) {
          ln++;
          lines.push({ text: line, type: 'remove', oldLineNo: ln, newLineNo: null });
        }
      }
      if (entry.newString) {
        lines.push({ text: '+++ b/' + (entry.filePath || 'file'), type: 'file', oldLineNo: null, newLineNo: null });
        let ln = 0;
        for (const line of entry.newString.split('\n')) {
          ln++;
          lines.push({ text: line, type: 'add', oldLineNo: null, newLineNo: ln });
        }
      }
    }

    return lines;
  }

  /** For split view: pair up remove/add lines side by side. */
  getSplitPairs(): Array<{ left: DiffLine | null; right: DiffLine | null }> {
    const pairs: Array<{ left: DiffLine | null; right: DiffLine | null }> = [];
    let i = 0;
    while (i < this.parsedDiff.length) {
      const line = this.parsedDiff[i];
      if (line.type === 'file' || line.type === 'header') {
        pairs.push({ left: line, right: line });
        i++;
      } else if (line.type === 'context') {
        pairs.push({ left: line, right: line });
        i++;
      } else if (line.type === 'remove') {
        // Collect consecutive removes
        const removes: DiffLine[] = [];
        while (i < this.parsedDiff.length && this.parsedDiff[i].type === 'remove') {
          removes.push(this.parsedDiff[i]);
          i++;
        }
        // Collect consecutive adds
        const adds: DiffLine[] = [];
        while (i < this.parsedDiff.length && this.parsedDiff[i].type === 'add') {
          adds.push(this.parsedDiff[i]);
          i++;
        }
        // Pair them
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

  // ── Helpers ──────────────────────────────────────────────────────

  getFileName(filePath: string): string {
    if (!filePath) return '';
    const parts = filePath.split('/');
    return parts[parts.length - 1];
  }

  private shortenDir(dir: string): string {
    if (!dir) return '/';
    // If very long, show last 3 segments
    const parts = dir.split('/').filter(p => p);
    if (parts.length > 3) {
      return '.../' + parts.slice(-3).join('/');
    }
    return dir;
  }

  getAgentIcon(agent: string): string {
    const icons: Record<string, string> = {
      'claude-code': 'smart_toy', 'codex': 'psychology', 'opencode': 'code',
      'qwen': 'auto_awesome', 'cline': 'terminal', 'cursor': 'mouse',
      'continue': 'play_arrow', 'aider': 'build', 'gemini': 'diamond',
      'kompile': 'memory', 'pi': 'circle'
    };
    return icons[agent] || 'smart_toy';
  }

  getDiffTypeColor(dt: string): string {
    return dt === 'edit' ? '#ff9800' : dt === 'write' ? '#4caf50' : dt === 'patch' ? '#2196f3' : '#9e9e9e';
  }

  getDiffTypeIcon(dt: string): string {
    return dt === 'edit' ? 'edit' : dt === 'write' ? 'note_add' : dt === 'patch' ? 'difference' : 'code';
  }

  formatDate(d: string): string {
    if (!d) return '';
    const date = new Date(d);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  formatNumber(n: number): string {
    if (!n) return '0';
    return n >= 1000 ? (n / 1000).toFixed(1) + 'K' : n.toString();
  }

  get filteredEntries(): DiffIndexEntry[] {
    let entries = this.allEntries;
    if (this.diffTypeFilter) entries = entries.filter(e => e.diffType === this.diffTypeFilter);
    return entries;
  }

  get activeFilterCount(): number {
    let c = 0;
    if (this.selectedProject) c++;
    if (this.selectedAgent) c++;
    if (this.searchQuery) c++;
    if (this.fileFilter) c++;
    if (this.diffTypeFilter) c++;
    return c;
  }
}
