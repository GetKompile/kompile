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

import { of } from 'rxjs';

import { CodeDiffBrowserComponent } from './code-diff-browser.component';
import { GitDiffService, GitCommit, GitFileDiff } from '@shared/services/git-diff.service';
import { DiffIndexService, DiffSession, DiffIndexEntry } from '@shared/services/diff-index.service';

/**
 * Logic tests for the three-mode diff browser. The component's only collaborators
 * are two services, so it is built with `new` + jasmine spies (no TestBed/DOM),
 * keeping the focus on mode switching, git/compare orchestration, and helpers.
 */
describe('CodeDiffBrowserComponent', () => {
  let component: CodeDiffBrowserComponent;
  let gitService: jasmine.SpyObj<GitDiffService>;
  let diffService: jasmine.SpyObj<DiffIndexService>;

  const commit: GitCommit = {
    hash: 'h1', shortHash: 'h1', author: 'A', email: 'a@x',
    dateIso: '2026-01-01T00:00:00Z', subject: 'c1', filesChanged: 1, linesAdded: 2, linesRemoved: 0
  };
  const fileDiff: GitFileDiff = {
    path: 'src/Foo.java', oldPath: null, changeType: 'MODIFIED',
    unifiedDiff: '@@ -1 +1 @@\n-a\n+b', linesAdded: 1, linesRemoved: 1, binary: false
  };
  const sessionA: DiffSession = {
    sessionId: 'sess-A', sessionFingerprint: 'fp', entryCount: 2, agents: ['claude-code'],
    agent: 'claude-code', sources: ['transcript'], projects: ['/proj/A'], fileCount: 1,
    totalLinesAdded: 3, totalLinesRemoved: 1, firstTimestamp: '2026-01-01T00:00:00Z', lastTimestamp: '2026-01-02T00:00:00Z'
  };
  const sessionB: DiffSession = {
    sessionId: 'sess-B', entryCount: 1, agents: ['codex'], agent: 'codex', sources: ['transcript'],
    projects: ['/proj/B'], fileCount: 1, totalLinesAdded: 2, totalLinesRemoved: 0,
    firstTimestamp: '2026-02-01T00:00:00Z', lastTimestamp: '2026-02-01T00:00:00Z'
  };
  const agentEntry: DiffIndexEntry = {
    id: 'a1', agent: 'claude-code', source: 'transcript', sessionId: 'sess-A', sessionFingerprint: 'fp',
    projectDirectory: '/proj/A', filePath: '/proj/A/Foo.java', toolName: 'Edit', diffType: 'edit',
    oldString: 'a', newString: 'b', unifiedDiff: null, timestamp: '2026-01-01T00:00:00Z', linesAdded: 1, linesRemoved: 1
  };

  beforeEach(() => {
    gitService = jasmine.createSpyObj<GitDiffService>('GitDiffService',
      ['status', 'branches', 'commits', 'commitDiff', 'fileHistory', 'fileAtRef']);
    diffService = jasmine.createSpyObj<DiffIndexService>('DiffIndexService', ['search', 'listSessions', 'sessionEntries']);

    gitService.status.and.returnValue(of({ repo: true, root: '/repo', branch: 'main' }));
    gitService.branches.and.returnValue(of(['main', 'dev']));
    gitService.commits.and.returnValue(of([commit]));
    gitService.commitDiff.and.returnValue(of([fileDiff]));
    gitService.fileHistory.and.returnValue(of([]));
    diffService.search.and.returnValue(of([]));
    diffService.listSessions.and.returnValue(of([sessionB, sessionA]));
    diffService.sessionEntries.and.returnValue(of([agentEntry]));

    component = new CodeDiffBrowserComponent(gitService, diffService);
  });

  it('should create and default to git mode / split view', () => {
    expect(component).toBeTruthy();
    expect(component.mode).toBe('git');
    expect(component.viewMode).toBe('split');
  });

  it('loads git status, branches and commits on init', () => {
    component.ngOnInit();
    expect(gitService.status).toHaveBeenCalled();
    expect(component.gitStatus?.repo).toBeTrue();
    expect(gitService.branches).toHaveBeenCalled();
    expect(component.commits.length).toBe(1);
  });

  it('selecting a commit loads its diffs and auto-opens the first file', () => {
    component.selectCommit(commit);
    expect(gitService.commitDiff).toHaveBeenCalledWith('h1');
    expect(component.commitFiles.length).toBe(1);
    expect(component.selectedCommitFile?.path).toBe('src/Foo.java');
  });

  it('counts active git filters', () => {
    expect(component.gitFilterCount).toBe(0);
    component.pathFilter = 'Foo';
    component.sinceFilter = '2026-01-01T00:00';
    expect(component.gitFilterCount).toBe(2);
  });

  it('setMode switches modes', () => {
    component.setMode('compare');
    expect(component.mode).toBe('compare');
    component.setMode('agent');
    expect(component.mode).toBe('agent');
  });

  it('runCompare strips the repo root from an absolute path for the git side only', () => {
    component.gitStatus = { repo: true, root: '/repo', branch: 'main' };
    component.comparePath = '/repo/src/Foo.java';
    component.runCompare();

    expect(gitService.fileHistory).toHaveBeenCalledWith('src/Foo.java', jasmine.objectContaining({ limit: 100 }));
    expect(diffService.search).toHaveBeenCalledWith(jasmine.objectContaining({ filePath: '/repo/src/Foo.java', limit: 200 }));
    expect(component.compareRan).toBeTrue();
    expect(component.loadingCompare).toBeFalse(); // both synchronous observables have settled
  });

  it('runCompare is a no-op when the path is blank', () => {
    component.comparePath = '   ';
    component.runCompare();
    expect(gitService.fileHistory).not.toHaveBeenCalled();
    expect(diffService.search).not.toHaveBeenCalled();
  });

  it('exposes change-type + agent styling helpers', () => {
    expect(component.changeTypeIcon('ADDED')).toBe('add_circle');
    expect(component.changeTypeIcon('DELETED')).toBe('remove_circle');
    expect(component.changeTypeIcon('RENAMED')).toBe('drive_file_rename_outline');
    expect(component.changeTypeColor('ADDED')).toBe('#22863a');
    expect(component.changeTypeColor('DELETED')).toBe('#cb2431');
    expect(component.getFileName('/a/b/c.java')).toBe('c.java');
    expect(component.getFileName(null)).toBe('');
    expect(component.formatNumber(1500)).toBe('1.5K');
    expect(component.formatNumber(0)).toBe('0');
    expect(component.getAgentIcon('claude-code')).toBe('smart_toy');
    expect(component.getAgentIcon('unknown-agent')).toBe('smart_toy');
  });

  it('lazily loads transcript sessions when first entering Sessions mode', () => {
    expect(diffService.listSessions).not.toHaveBeenCalled();
    component.setMode('sessions');
    expect(component.mode).toBe('sessions');
    expect(diffService.listSessions).toHaveBeenCalledTimes(1);
    expect(component.sessions.length).toBe(2);
    expect(component.sessionsLoaded).toBeTrue();

    // Re-entering does not reload.
    component.setMode('git');
    component.setMode('sessions');
    expect(diffService.listSessions).toHaveBeenCalledTimes(1);
  });

  it('selecting a session loads its diffs and auto-opens the first', () => {
    component.selectSession(sessionA);
    expect(diffService.sessionEntries).toHaveBeenCalledWith('sess-A');
    expect(component.sessionDiffs.length).toBe(1);
    expect(component.selectedSessionDiff?.id).toBe('a1');
  });

  it('filters sessions by agent and by id/project text', () => {
    component.sessions = [sessionB, sessionA];
    component.sessionAgentFilter = 'codex';
    expect(component.filteredSessions.map(s => s.sessionId)).toEqual(['sess-B']);

    component.sessionAgentFilter = '';
    component.sessionQuery = 'proj/A';
    expect(component.filteredSessions.map(s => s.sessionId)).toEqual(['sess-A']);
  });

  it('derives distinct agent options and shortens session ids', () => {
    component.sessions = [sessionB, sessionA];
    expect(component.sessionAgentOptions).toEqual(['claude-code', 'codex']);
    expect(component.shortSession('abcdefghijklmnopqrstuvwxyz')).toBe('abcdefgh…wxyz');
    expect(component.shortSession('short')).toBe('short');
  });
});
