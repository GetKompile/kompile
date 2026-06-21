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
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

/** Git commit metadata + line stats (from /api/git/commits). */
export interface GitCommit {
  hash: string;
  shortHash: string;
  author: string;
  email: string;
  dateIso: string;
  subject: string;
  filesChanged: number;
  linesAdded: number;
  linesRemoved: number;
}

/**
 * Diff of one file. Returned both by commit-diff (commit* fields null) and by
 * file-history (commit* fields populated). Its `unifiedDiff` is consumed by the
 * shared DiffViewComponent — the same renderer used for agent-change diffs.
 */
export interface GitFileDiff {
  path: string;
  oldPath: string | null;
  changeType: string;
  unifiedDiff: string | null;
  linesAdded: number;
  linesRemoved: number;
  binary: boolean;
  commitHash?: string | null;
  commitShortHash?: string | null;
  author?: string | null;
  dateIso?: string | null;
  subject?: string | null;
}

export interface GitStatus {
  repo: boolean;
  root: string;
  branch: string;
}

export interface GitCommitQuery {
  branch?: string;
  limit?: number;
  since?: string;
  until?: string;
  path?: string;
  query?: string;
}

@Injectable({ providedIn: 'root' })
export class GitDiffService {

  private readonly apiUrl = `${backendUrl}/git`;

  constructor(private http: HttpClient) {}

  status(): Observable<GitStatus> {
    return this.http.get<GitStatus>(`${this.apiUrl}/status`);
  }

  branches(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/branches`);
  }

  commits(q: GitCommitQuery): Observable<GitCommit[]> {
    let params = new HttpParams();
    if (q.branch) params = params.set('branch', q.branch);
    if (q.limit) params = params.set('limit', q.limit.toString());
    if (q.since) params = params.set('since', q.since);
    if (q.until) params = params.set('until', q.until);
    if (q.path) params = params.set('path', q.path);
    if (q.query) params = params.set('query', q.query);
    return this.http.get<GitCommit[]>(`${this.apiUrl}/commits`, { params });
  }

  commitDiff(hash: string): Observable<GitFileDiff[]> {
    return this.http.get<GitFileDiff[]>(`${this.apiUrl}/commits/${encodeURIComponent(hash)}/diff`);
  }

  fileHistory(path: string, opts: { limit?: number; since?: string; until?: string } = {}): Observable<GitFileDiff[]> {
    let params = new HttpParams().set('path', path);
    if (opts.limit) params = params.set('limit', opts.limit.toString());
    if (opts.since) params = params.set('since', opts.since);
    if (opts.until) params = params.set('until', opts.until);
    return this.http.get<GitFileDiff[]>(`${this.apiUrl}/file-history`, { params });
  }

  fileAtRef(ref: string, path: string): Observable<{ ref: string; path: string; content: string }> {
    const params = new HttpParams().set('ref', ref).set('path', path);
    return this.http.get<{ ref: string; path: string; content: string }>(`${this.apiUrl}/file`, { params });
  }
}
