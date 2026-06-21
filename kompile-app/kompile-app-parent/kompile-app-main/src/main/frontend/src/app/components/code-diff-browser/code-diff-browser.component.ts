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

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatChipsModule } from '@angular/material/chips';

import { DiffViewComponent } from '../diff-view/diff-view.component';
import { DiffBrowserComponent } from '../diff-browser/diff-browser.component';
import { DiffPolicyPanelComponent } from '../diff-policy-panel/diff-policy-panel.component';
import { GitDiffService, GitCommit, GitFileDiff, GitStatus } from '../../services/git-diff.service';
import { DiffIndexService, DiffIndexEntry, DiffSession } from '../../services/diff-index.service';

type DiffMode = 'git' | 'agent' | 'compare' | 'sessions' | 'policy';

/**
 * Unified diff-browsing surface for the code indexer. Three modes:
 *  - "Git Commits": browse the project repo's commit history (filter by time,
 *    file, branch, message), click a commit to see its per-file diffs side by side.
 *  - "Agent Changes": browse diffs mined from transcript tool calls across all
 *    agents (delegates to the existing DiffBrowserComponent).
 *  - "Compare": pick a file + time window and line up its git-committed history
 *    against the agent changes to it, viewing either side by side.
 */
@Component({
  selector: 'app-code-diff-browser',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatButtonModule, MatButtonToggleModule, MatIconModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatTooltipModule, MatProgressBarModule, MatChipsModule,
    DiffViewComponent, DiffBrowserComponent, DiffPolicyPanelComponent
  ],
  templateUrl: './code-diff-browser.component.html',
  styleUrls: ['./code-diff-browser.component.css']
})
export class CodeDiffBrowserComponent implements OnInit {

  mode: DiffMode = 'git';

  // ── Shared view options ──────────────────────────────────────────
  viewMode: 'unified' | 'split' = 'split';
  showLineNumbers = true;

  // ── Git mode ─────────────────────────────────────────────────────
  gitStatus: GitStatus | null = null;
  gitStatusLoaded = false;
  branches: string[] = [];
  commits: GitCommit[] = [];
  loadingCommits = false;

  branchFilter = '';
  sinceFilter = '';
  untilFilter = '';
  pathFilter = '';
  messageFilter = '';
  commitLimit = 100;

  selectedCommit: GitCommit | null = null;
  commitFiles: GitFileDiff[] = [];
  selectedCommitFile: GitFileDiff | null = null;
  loadingCommitDiff = false;

  // ── Compare mode ─────────────────────────────────────────────────
  comparePath = '';
  compareSince = '';
  compareUntil = '';
  gitHistory: GitFileDiff[] = [];
  agentChanges: DiffIndexEntry[] = [];
  selectedGitChange: GitFileDiff | null = null;
  selectedAgentChange: DiffIndexEntry | null = null;
  loadingCompare = false;
  compareRan = false;

  // ── Sessions mode ────────────────────────────────────────────────
  sessions: DiffSession[] = [];
  sessionsLoaded = false;
  loadingSessions = false;
  sessionAgentFilter = '';
  sessionQuery = '';
  selectedSession: DiffSession | null = null;
  sessionDiffs: DiffIndexEntry[] = [];
  selectedSessionDiff: DiffIndexEntry | null = null;
  loadingSessionDiffs = false;

  constructor(
    private gitService: GitDiffService,
    private diffService: DiffIndexService
  ) {}

  ngOnInit(): void {
    this.loadGitStatus();
  }

  setMode(mode: DiffMode): void {
    this.mode = mode;
    if (mode === 'git' && !this.gitStatusLoaded) {
      this.loadGitStatus();
    } else if (mode === 'sessions' && !this.sessionsLoaded) {
      this.loadSessions();
    }
  }

  // ── Git: status + commit list ────────────────────────────────────

  loadGitStatus(): void {
    this.gitService.status().subscribe({
      next: (s) => {
        this.gitStatus = s;
        this.gitStatusLoaded = true;
        if (s.repo) {
          this.branchFilter = '';
          this.loadBranches();
          this.loadCommits();
        }
      },
      error: () => {
        this.gitStatus = { repo: false, root: '', branch: '' };
        this.gitStatusLoaded = true;
      }
    });
  }

  loadBranches(): void {
    this.gitService.branches().subscribe({
      next: (b) => this.branches = b,
      error: () => this.branches = []
    });
  }

  loadCommits(): void {
    this.loadingCommits = true;
    this.selectedCommit = null;
    this.commitFiles = [];
    this.selectedCommitFile = null;
    this.gitService.commits({
      branch: this.branchFilter || undefined,
      limit: this.commitLimit,
      since: this.sinceFilter || undefined,
      until: this.untilFilter || undefined,
      path: this.pathFilter || undefined,
      query: this.messageFilter || undefined
    }).subscribe({
      next: (c) => { this.commits = c; this.loadingCommits = false; },
      error: () => { this.commits = []; this.loadingCommits = false; }
    });
  }

  clearGitFilters(): void {
    this.branchFilter = '';
    this.sinceFilter = '';
    this.untilFilter = '';
    this.pathFilter = '';
    this.messageFilter = '';
    this.loadCommits();
  }

  get gitFilterCount(): number {
    let n = 0;
    if (this.branchFilter) n++;
    if (this.sinceFilter) n++;
    if (this.untilFilter) n++;
    if (this.pathFilter) n++;
    if (this.messageFilter) n++;
    return n;
  }

  selectCommit(commit: GitCommit): void {
    this.selectedCommit = commit;
    this.selectedCommitFile = null;
    this.commitFiles = [];
    this.loadingCommitDiff = true;
    this.gitService.commitDiff(commit.hash).subscribe({
      next: (files) => {
        this.commitFiles = files;
        this.loadingCommitDiff = false;
        // Auto-open the first file so the side-by-side diff is visible immediately.
        if (files.length) this.selectedCommitFile = files[0];
      },
      error: () => { this.commitFiles = []; this.loadingCommitDiff = false; }
    });
  }

  selectCommitFile(file: GitFileDiff): void {
    this.selectedCommitFile = file;
  }

  // ── Compare: git history vs agent changes for one file ───────────

  runCompare(): void {
    const path = this.comparePath.trim();
    if (!path) return;
    this.loadingCompare = true;
    this.compareRan = true;
    this.selectedGitChange = null;
    this.selectedAgentChange = null;
    this.gitHistory = [];
    this.agentChanges = [];
    this.comparePending = 2;

    // git pathspecs are repo-relative; strip the repo root if an absolute path was pasted.
    let gitPath = path;
    const root = this.gitStatus?.root;
    if (root && gitPath.startsWith(root)) {
      gitPath = gitPath.substring(root.length).replace(/^\/+/, '');
    }

    this.gitService.fileHistory(gitPath, {
      limit: 100,
      since: this.compareSince || undefined,
      until: this.compareUntil || undefined
    }).subscribe({
      next: (h) => {
        this.gitHistory = h;
        if (h.length) this.selectedGitChange = h[0];
        this.settleCompare();
      },
      error: () => { this.gitHistory = []; this.settleCompare(); }
    });

    this.diffService.search({
      filePath: path,
      since: this.compareSince || undefined,
      until: this.compareUntil || undefined,
      limit: 200
    }).subscribe({
      next: (a) => {
        this.agentChanges = a;
        if (a.length) this.selectedAgentChange = a[0];
        this.settleCompare();
      },
      error: () => { this.agentChanges = []; this.settleCompare(); }
    });
  }

  private comparePending = 0;
  /** Stop the progress bar once both the git-history and agent-changes calls settle. */
  private settleCompare(): void {
    if (--this.comparePending <= 0) {
      this.loadingCompare = false;
      this.comparePending = 0;
    }
  }

  selectGitChange(d: GitFileDiff): void {
    this.selectedGitChange = d;
  }

  selectAgentChange(e: DiffIndexEntry): void {
    this.selectedAgentChange = e;
  }

  // ── Sessions: browse mined diffs across transcript sessions ──────

  loadSessions(): void {
    this.loadingSessions = true;
    this.selectedSession = null;
    this.sessionDiffs = [];
    this.selectedSessionDiff = null;
    this.diffService.listSessions().subscribe({
      next: (s) => { this.sessions = s; this.sessionsLoaded = true; this.loadingSessions = false; },
      error: () => { this.sessions = []; this.sessionsLoaded = true; this.loadingSessions = false; }
    });
  }

  selectSession(session: DiffSession): void {
    this.selectedSession = session;
    this.sessionDiffs = [];
    this.selectedSessionDiff = null;
    this.loadingSessionDiffs = true;
    this.diffService.sessionEntries(session.sessionId).subscribe({
      next: (entries) => {
        this.sessionDiffs = entries;
        this.loadingSessionDiffs = false;
        if (entries.length) this.selectedSessionDiff = entries[0];
      },
      error: () => { this.sessionDiffs = []; this.loadingSessionDiffs = false; }
    });
  }

  selectSessionDiff(entry: DiffIndexEntry): void {
    this.selectedSessionDiff = entry;
  }

  /** Distinct agents across the loaded sessions, for the agent filter dropdown. */
  get sessionAgentOptions(): string[] {
    const set = new Set<string>();
    for (const s of this.sessions) {
      if (s.agent) set.add(s.agent);
      (s.agents || []).forEach(a => set.add(a));
    }
    return Array.from(set).sort();
  }

  get filteredSessions(): DiffSession[] {
    const q = this.sessionQuery.trim().toLowerCase();
    return this.sessions.filter(s => {
      if (this.sessionAgentFilter) {
        const inAgents = s.agent === this.sessionAgentFilter || (s.agents || []).includes(this.sessionAgentFilter);
        if (!inAgents) return false;
      }
      if (q) {
        const hay = (s.sessionId + ' ' + (s.projects || []).join(' ')).toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }

  /** Compact display form of a (often UUID-shaped) session id. */
  shortSession(sessionId: string): string {
    if (!sessionId) return '';
    return sessionId.length > 12 ? sessionId.substring(0, 8) + '…' + sessionId.slice(-4) : sessionId;
  }

  // ── Helpers ──────────────────────────────────────────────────────

  getFileName(filePath: string | null | undefined): string {
    if (!filePath) return '';
    const parts = filePath.split('/');
    return parts[parts.length - 1];
  }

  changeTypeColor(t: string | null | undefined): string {
    switch (t) {
      case 'ADDED': return '#22863a';
      case 'DELETED': return '#cb2431';
      case 'RENAMED': return '#6f42c1';
      case 'COPIED': return '#0969da';
      default: return '#b08800';
    }
  }

  changeTypeIcon(t: string | null | undefined): string {
    switch (t) {
      case 'ADDED': return 'add_circle';
      case 'DELETED': return 'remove_circle';
      case 'RENAMED': return 'drive_file_rename_outline';
      case 'COPIED': return 'content_copy';
      default: return 'edit';
    }
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

  formatDate(d: string | null | undefined): string {
    if (!d) return '';
    const date = new Date(d);
    if (isNaN(date.getTime())) return d;
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  formatNumber(n: number): string {
    if (!n) return '0';
    return n >= 1000 ? (n / 1000).toFixed(1) + 'K' : n.toString();
  }
}
