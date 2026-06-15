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
import { tap, catchError } from 'rxjs/operators';
import { BaseService, backendUrl } from './base.service';

/**
 * Confluence page metadata
 */
export interface ConfluencePage {
  id: string;
  title: string;
  spaceKey: string;
  spaceName?: string;
  type: 'page' | 'blogpost' | 'attachment';
  status: string;
  createdDate?: string;
  modifiedDate?: string;
  createdBy?: string;
  lastModifiedBy?: string;
  webUrl?: string;
  version?: number;
  ancestors?: { id: string; title: string }[];
  children?: { page?: { size: number }; attachment?: { size: number } };
  hasChildren?: boolean;
}

/**
 * Confluence space metadata
 */
export interface ConfluenceSpace {
  id: string;
  key: string;
  name: string;
  description?: string;
  type: 'global' | 'personal';
  status: string;
  homepageId?: string;
  iconUrl?: string;
}

/**
 * Response from listing pages
 */
export interface ConfluencePageListResponse {
  pages: ConfluencePage[];
  start: number;
  limit: number;
  size: number;
  totalSize?: number;
}

/**
 * Response from listing spaces
 */
export interface ConfluenceSpaceListResponse {
  spaces: ConfluenceSpace[];
  start: number;
  limit: number;
  size: number;
  totalSize?: number;
}

/**
 * Confluence connection status
 */
export interface ConfluenceConnectionStatus {
  connected: boolean;
  baseUrl?: string;
  username?: string;
  displayName?: string;
  cloudId?: string;
  serverVersion?: string;
  deploymentType?: 'cloud' | 'server' | 'datacenter';
}

/**
 * Confluence connection configuration
 */
export interface ConfluenceConnectionConfig {
  baseUrl: string;
  email: string;
  apiToken: string;
  cloudId?: string;
}

/**
 * Confluence ingest request
 */
export interface ConfluenceIngestRequest {
  pageIds: string[];
  spaceKeys?: string[];
  includeChildren?: boolean;
  includeAttachments?: boolean;
  includeComments?: boolean;
  maxDepth?: number;
  chunkerName?: string;
  processingMode?: 'auto' | 'subprocess' | 'inprocess';
}

/**
 * Confluence ingest response
 */
export interface ConfluenceIngestResponse {
  taskIds: string[];
  pagesQueued: number;
  message: string;
}

/**
 * Confluence search request
 */
export interface ConfluenceSearchRequest {
  cql?: string;
  spaceKey?: string;
  title?: string;
  type?: 'page' | 'blogpost' | 'all';
  start?: number;
  limit?: number;
}

/**
 * Confluence label
 */
export interface ConfluenceLabel {
  id: string;
  name: string;
  prefix: string;
}

@Injectable({
  providedIn: 'root'
})
export class ConfluenceService extends BaseService {
  private connectionStatusSubject = new BehaviorSubject<ConfluenceConnectionStatus>({ connected: false });
  public connectionStatus$ = this.connectionStatusSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
    // Check connection status on service init
    this.checkConnectionStatus();
  }

  /**
   * Check if Confluence is connected
   */
  checkConnectionStatus(): Observable<ConfluenceConnectionStatus> {
    return this.http.get<ConfluenceConnectionStatus>(`${backendUrl}/confluence/status`).pipe(
      tap(status => this.connectionStatusSubject.next(status)),
      catchError(err => {
        console.error('Error checking Confluence connection status:', err);
        const status: ConfluenceConnectionStatus = { connected: false };
        this.connectionStatusSubject.next(status);
        return of(status);
      })
    );
  }

  /**
   * Connect to Confluence with API token
   */
  connect(config: ConfluenceConnectionConfig): Observable<ConfluenceConnectionStatus> {
    return this.http.post<ConfluenceConnectionStatus>(`${backendUrl}/confluence/connect`, config).pipe(
      tap(status => this.connectionStatusSubject.next(status)),
      catchError(err => {
        console.error('Error connecting to Confluence:', err);
        throw err;
      })
    );
  }

  /**
   * Disconnect from Confluence
   */
  disconnect(): Observable<{ success: boolean }> {
    return this.http.post<{ success: boolean }>(`${backendUrl}/confluence/disconnect`, {}).pipe(
      tap(() => this.connectionStatusSubject.next({ connected: false }))
    );
  }

  /**
   * List spaces
   */
  listSpaces(start?: number, limit?: number): Observable<ConfluenceSpaceListResponse> {
    let params = new HttpParams();
    if (start !== undefined) {
      params = params.set('start', start.toString());
    }
    if (limit !== undefined) {
      params = params.set('limit', limit.toString());
    }
    return this.http.get<ConfluenceSpaceListResponse>(`${backendUrl}/confluence/spaces`, { params });
  }

  /**
   * Get a specific space
   */
  getSpace(spaceKey: string): Observable<ConfluenceSpace> {
    return this.http.get<ConfluenceSpace>(`${backendUrl}/confluence/spaces/${spaceKey}`);
  }

  /**
   * List pages in a space
   */
  listPages(spaceKey: string, start?: number, limit?: number, parentId?: string): Observable<ConfluencePageListResponse> {
    let params = new HttpParams();
    if (start !== undefined) {
      params = params.set('start', start.toString());
    }
    if (limit !== undefined) {
      params = params.set('limit', limit.toString());
    }
    if (parentId) {
      params = params.set('parentId', parentId);
    }
    return this.http.get<ConfluencePageListResponse>(`${backendUrl}/confluence/spaces/${spaceKey}/pages`, { params });
  }

  /**
   * Search pages with CQL or filters
   */
  searchPages(request: ConfluenceSearchRequest): Observable<ConfluencePageListResponse> {
    let params = new HttpParams();

    if (request.cql) {
      params = params.set('cql', request.cql);
    }
    if (request.spaceKey) {
      params = params.set('spaceKey', request.spaceKey);
    }
    if (request.title) {
      params = params.set('title', request.title);
    }
    if (request.type && request.type !== 'all') {
      params = params.set('type', request.type);
    }
    if (request.start !== undefined) {
      params = params.set('start', request.start.toString());
    }
    if (request.limit !== undefined) {
      params = params.set('limit', request.limit.toString());
    }

    return this.http.get<ConfluencePageListResponse>(`${backendUrl}/confluence/search`, { params });
  }

  /**
   * Get a single page by ID
   */
  getPage(pageId: string): Observable<ConfluencePage> {
    return this.http.get<ConfluencePage>(`${backendUrl}/confluence/pages/${pageId}`);
  }

  /**
   * Get page children
   */
  getPageChildren(pageId: string, start?: number, limit?: number): Observable<ConfluencePageListResponse> {
    let params = new HttpParams();
    if (start !== undefined) {
      params = params.set('start', start.toString());
    }
    if (limit !== undefined) {
      params = params.set('limit', limit.toString());
    }
    return this.http.get<ConfluencePageListResponse>(`${backendUrl}/confluence/pages/${pageId}/children`, { params });
  }

  /**
   * Get page labels
   */
  getPageLabels(pageId: string): Observable<ConfluenceLabel[]> {
    return this.http.get<ConfluenceLabel[]>(`${backendUrl}/confluence/pages/${pageId}/labels`);
  }

  /**
   * Ingest selected pages
   */
  ingestPages(request: ConfluenceIngestRequest): Observable<ConfluenceIngestResponse> {
    return this.http.post<ConfluenceIngestResponse>(`${backendUrl}/confluence/ingest`, request);
  }

  /**
   * Ingest an entire space
   */
  ingestSpace(spaceKey: string, options?: Partial<ConfluenceIngestRequest>): Observable<ConfluenceIngestResponse> {
    return this.http.post<ConfluenceIngestResponse>(`${backendUrl}/confluence/spaces/${spaceKey}/ingest`, options || {});
  }

  /**
   * Get icon for page type
   */
  getPageTypeIcon(type: string): string {
    switch (type?.toLowerCase()) {
      case 'page':
        return 'article';
      case 'blogpost':
        return 'post_add';
      case 'attachment':
        return 'attach_file';
      default:
        return 'description';
    }
  }

  /**
   * Get icon class for page type
   */
  getPageTypeIconClass(type: string): string {
    switch (type?.toLowerCase()) {
      case 'page':
        return 'icon-confluence-page';
      case 'blogpost':
        return 'icon-confluence-blog';
      case 'attachment':
        return 'icon-confluence-attachment';
      default:
        return 'icon-confluence-default';
    }
  }

  /**
   * Get icon for space type
   */
  getSpaceTypeIcon(type: string): string {
    switch (type?.toLowerCase()) {
      case 'global':
        return 'public';
      case 'personal':
        return 'person';
      default:
        return 'folder';
    }
  }

  /**
   * Format page breadcrumb from ancestors
   */
  formatBreadcrumb(page: ConfluencePage): string {
    if (!page.ancestors || page.ancestors.length === 0) {
      return page.title;
    }
    const path = page.ancestors.map(a => a.title).join(' > ');
    return `${path} > ${page.title}`;
  }

  /**
   * Format date for display
   */
  formatDate(dateString?: string): string {
    if (!dateString) return '';
    try {
      const date = new Date(dateString);
      return date.toLocaleDateString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric'
      });
    } catch {
      return dateString;
    }
  }
}
