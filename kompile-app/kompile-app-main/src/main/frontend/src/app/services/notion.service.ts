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
 * Notion page metadata
 */
export interface NotionPage {
  id: string;
  title: string;
  url: string;
  createdTime: string;
  lastEditedTime: string;
  icon?: NotionIcon;
  cover?: string;
  parentType: 'workspace' | 'page' | 'database';
  parentId?: string;
  archived: boolean;
  properties?: { [key: string]: any };
}

/**
 * Notion database metadata
 */
export interface NotionDatabase {
  id: string;
  title: string;
  url: string;
  createdTime: string;
  lastEditedTime: string;
  icon?: NotionIcon;
  cover?: string;
  isInline: boolean;
  archived: boolean;
  properties?: { [key: string]: NotionDatabaseProperty };
}

/**
 * Notion database property definition
 */
export interface NotionDatabaseProperty {
  id: string;
  name: string;
  type: string;
}

/**
 * Notion icon (emoji or external URL)
 */
export interface NotionIcon {
  type: 'emoji' | 'external' | 'file';
  emoji?: string;
  external?: { url: string };
  file?: { url: string };
}

/**
 * Response from listing pages
 */
export interface NotionPageListResponse {
  pages: NotionPage[];
  hasMore: boolean;
  nextCursor?: string;
}

/**
 * Response from listing databases
 */
export interface NotionDatabaseListResponse {
  databases: NotionDatabase[];
  hasMore: boolean;
  nextCursor?: string;
}

/**
 * Notion connection status
 */
export interface NotionConnectionStatus {
  connected: boolean;
  workspaceName?: string;
  workspaceIcon?: string;
  botId?: string;
}

/**
 * Notion connection configuration
 */
export interface NotionConnectionConfig {
  integrationToken: string;
}

/**
 * Notion ingest request
 */
export interface NotionIngestRequest {
  pageIds: string[];
  includeSubpages?: boolean;
  includeDatabases?: boolean;
  chunkerName?: string;
  processingMode?: 'auto' | 'subprocess' | 'inprocess';
}

/**
 * Notion ingest response
 */
export interface NotionIngestResponse {
  taskIds: string[];
  pagesQueued: number;
  message: string;
}

/**
 * Notion search request
 */
export interface NotionSearchRequest {
  query?: string;
  filter?: 'page' | 'database';
  startCursor?: string;
  pageSize?: number;
}

/**
 * Combined search result (pages and databases)
 */
export interface NotionSearchResponse {
  results: (NotionPage | NotionDatabase)[];
  hasMore: boolean;
  nextCursor?: string;
}

@Injectable({
  providedIn: 'root'
})
export class NotionService extends BaseService {
  private connectionStatusSubject = new BehaviorSubject<NotionConnectionStatus>({ connected: false });
  public connectionStatus$ = this.connectionStatusSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
    // Check connection status on service init
    this.checkConnectionStatus();
  }

  /**
   * Check if Notion is connected
   */
  checkConnectionStatus(): Observable<NotionConnectionStatus> {
    return this.http.get<NotionConnectionStatus>(`${backendUrl}/notion/status`).pipe(
      tap(status => this.connectionStatusSubject.next(status)),
      catchError(err => {
        console.error('Error checking Notion connection status:', err);
        const status: NotionConnectionStatus = { connected: false };
        this.connectionStatusSubject.next(status);
        return of(status);
      })
    );
  }

  /**
   * Connect to Notion with integration token
   */
  connect(config: NotionConnectionConfig): Observable<NotionConnectionStatus> {
    return this.http.post<NotionConnectionStatus>(`${backendUrl}/notion/connect`, config).pipe(
      tap(status => this.connectionStatusSubject.next(status)),
      catchError(err => {
        console.error('Error connecting to Notion:', err);
        throw err;
      })
    );
  }

  /**
   * Disconnect from Notion
   */
  disconnect(): Observable<{ success: boolean }> {
    return this.http.post<{ success: boolean }>(`${backendUrl}/notion/disconnect`, {}).pipe(
      tap(() => this.connectionStatusSubject.next({ connected: false }))
    );
  }

  /**
   * List pages accessible to the integration
   */
  listPages(cursor?: string, pageSize: number = 50): Observable<NotionPageListResponse> {
    let params = new HttpParams().set('pageSize', pageSize.toString());

    if (cursor) {
      params = params.set('cursor', cursor);
    }

    return this.http.get<NotionPageListResponse>(`${backendUrl}/notion/pages`, { params });
  }

  /**
   * List databases accessible to the integration
   */
  listDatabases(cursor?: string, pageSize: number = 50): Observable<NotionDatabaseListResponse> {
    let params = new HttpParams().set('pageSize', pageSize.toString());

    if (cursor) {
      params = params.set('cursor', cursor);
    }

    return this.http.get<NotionDatabaseListResponse>(`${backendUrl}/notion/databases`, { params });
  }

  /**
   * Search pages and databases
   */
  search(request: NotionSearchRequest): Observable<NotionSearchResponse> {
    let params = new HttpParams();

    if (request.query) {
      params = params.set('query', request.query);
    }
    if (request.filter) {
      params = params.set('filter', request.filter);
    }
    if (request.startCursor) {
      params = params.set('startCursor', request.startCursor);
    }
    if (request.pageSize) {
      params = params.set('pageSize', request.pageSize.toString());
    }

    return this.http.get<NotionSearchResponse>(`${backendUrl}/notion/search`, { params });
  }

  /**
   * Get a single page by ID
   */
  getPage(pageId: string): Observable<NotionPage> {
    return this.http.get<NotionPage>(`${backendUrl}/notion/pages/${pageId}`);
  }

  /**
   * Get a single database by ID
   */
  getDatabase(databaseId: string): Observable<NotionDatabase> {
    return this.http.get<NotionDatabase>(`${backendUrl}/notion/databases/${databaseId}`);
  }

  /**
   * Ingest selected pages
   */
  ingestPages(request: NotionIngestRequest): Observable<NotionIngestResponse> {
    return this.http.post<NotionIngestResponse>(`${backendUrl}/notion/ingest`, request);
  }

  /**
   * Get icon for page/database based on icon type
   */
  getItemIcon(icon: NotionIcon | undefined): string {
    if (!icon) return 'description';

    if (icon.type === 'emoji' && icon.emoji) {
      return icon.emoji;
    }
    return 'description';
  }

  /**
   * Check if icon is an emoji
   */
  isEmojiIcon(icon: NotionIcon | undefined): boolean {
    return icon?.type === 'emoji' && !!icon.emoji;
  }

  /**
   * Get icon URL for external/file icons
   */
  getIconUrl(icon: NotionIcon | undefined): string | null {
    if (!icon) return null;

    if (icon.type === 'external' && icon.external?.url) {
      return icon.external.url;
    }
    if (icon.type === 'file' && icon.file?.url) {
      return icon.file.url;
    }
    return null;
  }

  /**
   * Get icon class for material icon
   */
  getItemIconClass(isDatabase: boolean): string {
    return isDatabase ? 'icon-notion-database' : 'icon-notion-page';
  }

  /**
   * Get material icon for item type
   */
  getMaterialIcon(isDatabase: boolean): string {
    return isDatabase ? 'table_chart' : 'description';
  }

  /**
   * Check if item is a database
   */
  isDatabase(item: NotionPage | NotionDatabase): item is NotionDatabase {
    return 'isInline' in item;
  }

  /**
   * Format date for display
   */
  formatDate(dateString: string | undefined): string {
    if (!dateString) return '';
    const date = new Date(dateString);
    return date.toLocaleDateString();
  }

  /**
   * Get parent type display name
   */
  getParentTypeLabel(parentType: string): string {
    switch (parentType) {
      case 'workspace': return 'Workspace';
      case 'page': return 'Page';
      case 'database': return 'Database';
      default: return parentType;
    }
  }
}
