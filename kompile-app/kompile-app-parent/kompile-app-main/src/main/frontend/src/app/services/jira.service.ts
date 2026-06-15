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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, BehaviorSubject, of } from 'rxjs';
import { map, tap, catchError } from 'rxjs/operators';
import { BaseService, backendUrl } from './base.service';

/**
 * Jira issue metadata
 */
export interface JiraIssue {
  id: string;
  key: string;
  summary: string;
  description?: string;
  issueType: string;
  issueTypeIconUrl?: string;
  status: string;
  statusCategory: 'todo' | 'in_progress' | 'done';
  priority?: string;
  priorityIconUrl?: string;
  assignee?: string;
  assigneeAvatarUrl?: string;
  reporter?: string;
  created: string;
  updated: string;
  project: string;
  projectKey: string;
  labels?: string[];
  components?: string[];
}

/**
 * Jira project metadata
 */
export interface JiraProject {
  id: string;
  key: string;
  name: string;
  avatarUrl?: string;
  projectTypeKey: string;
  lead?: string;
}

/**
 * Response from listing issues
 */
export interface JiraIssueListResponse {
  issues: JiraIssue[];
  total: number;
  startAt: number;
  maxResults: number;
}

/**
 * Response from listing projects
 */
export interface JiraProjectListResponse {
  projects: JiraProject[];
  total: number;
}

/**
 * Jira connection status
 */
export interface JiraConnectionStatus {
  connected: boolean;
  baseUrl?: string;
  username?: string;
  displayName?: string;
  avatarUrl?: string;
  serverTitle?: string;
}

/**
 * Jira connection configuration
 */
export interface JiraConnectionConfig {
  baseUrl: string;
  email: string;
  apiToken: string;
}

/**
 * Jira ingest request
 */
export interface JiraIngestRequest {
  issueKeys: string[];
  includeComments?: boolean;
  includeAttachments?: boolean;
  chunkerName?: string;
  processingMode?: 'auto' | 'subprocess' | 'inprocess';
}

/**
 * Jira ingest response
 */
export interface JiraIngestResponse {
  taskIds: string[];
  issuesQueued: number;
  message: string;
}

/**
 * Jira JQL search request
 */
export interface JiraSearchRequest {
  jql?: string;
  projectKey?: string;
  issueType?: string;
  status?: string;
  assignee?: string;
  startAt?: number;
  maxResults?: number;
}

/**
 * Issue type info
 */
export interface JiraIssueType {
  id: string;
  name: string;
  iconUrl: string;
  subtask: boolean;
}

/**
 * Status info
 */
export interface JiraStatus {
  id: string;
  name: string;
  statusCategory: string;
}

@Injectable({
  providedIn: 'root'
})
export class JiraService extends BaseService {
  private connectionStatusSubject = new BehaviorSubject<JiraConnectionStatus>({ connected: false });
  public connectionStatus$ = this.connectionStatusSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
    // Check connection status on service init
    this.checkConnectionStatus();
  }

  /**
   * Check if Jira is connected
   */
  checkConnectionStatus(): Observable<JiraConnectionStatus> {
    return this.http.get<JiraConnectionStatus>(`${backendUrl}/jira/status`).pipe(
      tap(status => this.connectionStatusSubject.next(status)),
      catchError(err => {
        console.error('Error checking Jira connection status:', err);
        const status: JiraConnectionStatus = { connected: false };
        this.connectionStatusSubject.next(status);
        return of(status);
      })
    );
  }

  /**
   * Connect to Jira with API token
   */
  connect(config: JiraConnectionConfig): Observable<JiraConnectionStatus> {
    return this.http.post<JiraConnectionStatus>(`${backendUrl}/jira/connect`, config).pipe(
      tap(status => this.connectionStatusSubject.next(status)),
      catchError(err => {
        console.error('Error connecting to Jira:', err);
        throw err;
      })
    );
  }

  /**
   * Disconnect from Jira
   */
  disconnect(): Observable<{ success: boolean }> {
    return this.http.post<{ success: boolean }>(`${backendUrl}/jira/disconnect`, {}).pipe(
      tap(() => this.connectionStatusSubject.next({ connected: false }))
    );
  }

  /**
   * List projects
   */
  listProjects(): Observable<JiraProjectListResponse> {
    return this.http.get<JiraProjectListResponse>(`${backendUrl}/jira/projects`);
  }

  /**
   * Search issues with JQL or filters
   */
  searchIssues(request: JiraSearchRequest): Observable<JiraIssueListResponse> {
    let params = new HttpParams();

    if (request.jql) {
      params = params.set('jql', request.jql);
    }
    if (request.projectKey) {
      params = params.set('projectKey', request.projectKey);
    }
    if (request.issueType) {
      params = params.set('issueType', request.issueType);
    }
    if (request.status) {
      params = params.set('status', request.status);
    }
    if (request.assignee) {
      params = params.set('assignee', request.assignee);
    }
    if (request.startAt !== undefined) {
      params = params.set('startAt', request.startAt.toString());
    }
    if (request.maxResults !== undefined) {
      params = params.set('maxResults', request.maxResults.toString());
    }

    return this.http.get<JiraIssueListResponse>(`${backendUrl}/jira/issues`, { params });
  }

  /**
   * Get issue types for a project
   */
  getIssueTypes(projectKey?: string): Observable<JiraIssueType[]> {
    let params = new HttpParams();
    if (projectKey) {
      params = params.set('projectKey', projectKey);
    }
    return this.http.get<JiraIssueType[]>(`${backendUrl}/jira/issue-types`, { params });
  }

  /**
   * Get statuses for a project
   */
  getStatuses(projectKey?: string): Observable<JiraStatus[]> {
    let params = new HttpParams();
    if (projectKey) {
      params = params.set('projectKey', projectKey);
    }
    return this.http.get<JiraStatus[]>(`${backendUrl}/jira/statuses`, { params });
  }

  /**
   * Get a single issue by key
   */
  getIssue(issueKey: string): Observable<JiraIssue> {
    return this.http.get<JiraIssue>(`${backendUrl}/jira/issues/${issueKey}`);
  }

  /**
   * Ingest selected issues
   */
  ingestIssues(request: JiraIngestRequest): Observable<JiraIngestResponse> {
    return this.http.post<JiraIngestResponse>(`${backendUrl}/jira/ingest`, request);
  }

  /**
   * Get icon for issue type
   */
  getIssueTypeIcon(issueType: string): string {
    const typeMap: { [key: string]: string } = {
      'bug': 'bug_report',
      'task': 'task_alt',
      'story': 'auto_stories',
      'epic': 'bolt',
      'subtask': 'subdirectory_arrow_right',
      'improvement': 'trending_up',
      'new feature': 'new_releases',
      'feature': 'star'
    };
    return typeMap[issueType.toLowerCase()] || 'assignment';
  }

  /**
   * Get icon class for issue type
   */
  getIssueTypeIconClass(issueType: string): string {
    const classMap: { [key: string]: string } = {
      'bug': 'icon-jira-bug',
      'task': 'icon-jira-task',
      'story': 'icon-jira-story',
      'epic': 'icon-jira-epic',
      'subtask': 'icon-jira-subtask'
    };
    return classMap[issueType.toLowerCase()] || 'icon-jira-default';
  }

  /**
   * Get color for status category
   */
  getStatusCategoryColor(category: string): string {
    switch (category) {
      case 'todo': return '#42526e';
      case 'in_progress': return '#0052cc';
      case 'done': return '#00875a';
      default: return '#42526e';
    }
  }

  /**
   * Get icon for status category
   */
  getStatusCategoryIcon(category: string): string {
    switch (category) {
      case 'todo': return 'radio_button_unchecked';
      case 'in_progress': return 'timelapse';
      case 'done': return 'check_circle';
      default: return 'help_outline';
    }
  }

  /**
   * Format issue for display
   */
  formatIssueTitle(issue: JiraIssue): string {
    return `${issue.key}: ${issue.summary}`;
  }
}
