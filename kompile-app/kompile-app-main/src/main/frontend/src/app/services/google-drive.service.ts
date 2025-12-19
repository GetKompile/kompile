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
 * Google Drive file metadata
 */
export interface GoogleDriveFile {
  id: string;
  name: string;
  mimeType: string;
  size?: number;
  modifiedTime?: string;
  createdTime?: string;
  iconLink?: string;
  thumbnailLink?: string;
  webViewLink?: string;
  parents?: string[];
  shared?: boolean;
  starred?: boolean;
  trashed?: boolean;
}

/**
 * Google Drive folder structure
 */
export interface GoogleDriveFolder {
  id: string;
  name: string;
  files: GoogleDriveFile[];
  folders: GoogleDriveFolder[];
}

/**
 * Response from listing files
 */
export interface GoogleDriveListResponse {
  files: GoogleDriveFile[];
  nextPageToken?: string;
  incompleteSearch?: boolean;
}

/**
 * Google OAuth status
 */
export interface GoogleAuthStatus {
  authenticated: boolean;
  email?: string;
  name?: string;
  picture?: string;
  expiresAt?: string;
}

/**
 * Google Drive ingest request
 */
export interface GoogleDriveIngestRequest {
  fileIds: string[];
  chunkerName?: string;
  processingMode?: 'auto' | 'subprocess' | 'inprocess';
}

/**
 * Google Drive ingest response
 */
export interface GoogleDriveIngestResponse {
  taskIds: string[];
  filesQueued: number;
  message: string;
}

/**
 * Google Workspace MIME types
 */
export const GOOGLE_WORKSPACE_MIME_TYPES = {
  // Google Workspace types
  DOCUMENT: 'application/vnd.google-apps.document',
  SPREADSHEET: 'application/vnd.google-apps.spreadsheet',
  PRESENTATION: 'application/vnd.google-apps.presentation',
  DRAWING: 'application/vnd.google-apps.drawing',
  FORM: 'application/vnd.google-apps.form',
  SCRIPT: 'application/vnd.google-apps.script',
  SITE: 'application/vnd.google-apps.site',
  FOLDER: 'application/vnd.google-apps.folder',
  SHORTCUT: 'application/vnd.google-apps.shortcut',

  // Standard types
  PDF: 'application/pdf',
  WORD: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  EXCEL: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  POWERPOINT: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  TEXT: 'text/plain',
  HTML: 'text/html',
  CSV: 'text/csv',
  JSON: 'application/json',
  XML: 'application/xml'
};

@Injectable({
  providedIn: 'root'
})
export class GoogleDriveService extends BaseService {
  private authStatusSubject = new BehaviorSubject<GoogleAuthStatus>({ authenticated: false });
  public authStatus$ = this.authStatusSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
    // Check auth status on service init
    this.checkAuthStatus();
  }

  /**
   * Check if user is authenticated with Google
   */
  checkAuthStatus(): Observable<GoogleAuthStatus> {
    return this.http.get<GoogleAuthStatus>(`${backendUrl}/google/auth/status`).pipe(
      tap(status => this.authStatusSubject.next(status)),
      catchError(err => {
        console.error('Error checking Google auth status:', err);
        const status: GoogleAuthStatus = { authenticated: false };
        this.authStatusSubject.next(status);
        return of(status);
      })
    );
  }

  /**
   * Get the Google OAuth authorization URL
   */
  getAuthUrl(): Observable<{ url: string }> {
    return this.http.get<{ url: string }>(`${backendUrl}/google/auth/url`);
  }

  /**
   * Initiate OAuth flow - opens popup window
   */
  initiateAuth(): Promise<boolean> {
    return new Promise((resolve, reject) => {
      this.getAuthUrl().subscribe({
        next: (response) => {
          // Open OAuth popup
          const width = 600;
          const height = 700;
          const left = window.screenX + (window.outerWidth - width) / 2;
          const top = window.screenY + (window.outerHeight - height) / 2;

          const popup = window.open(
            response.url,
            'Google Sign In',
            `width=${width},height=${height},left=${left},top=${top},popup=1`
          );

          if (!popup) {
            reject(new Error('Popup blocked. Please allow popups for this site.'));
            return;
          }

          // Poll for popup close and check auth status
          const pollTimer = setInterval(() => {
            if (popup.closed) {
              clearInterval(pollTimer);
              // Check if auth was successful
              this.checkAuthStatus().subscribe({
                next: (status) => {
                  resolve(status.authenticated);
                },
                error: (err) => {
                  reject(err);
                }
              });
            }
          }, 500);

          // Timeout after 5 minutes
          setTimeout(() => {
            clearInterval(pollTimer);
            if (!popup.closed) {
              popup.close();
            }
            reject(new Error('Authentication timed out'));
          }, 300000);
        },
        error: (err) => {
          reject(err);
        }
      });
    });
  }

  /**
   * Disconnect Google account
   */
  disconnect(): Observable<{ success: boolean }> {
    return this.http.post<{ success: boolean }>(`${backendUrl}/google/auth/disconnect`, {}).pipe(
      tap(() => this.authStatusSubject.next({ authenticated: false }))
    );
  }

  /**
   * List files in Google Drive
   */
  listFiles(
    folderId?: string,
    pageToken?: string,
    pageSize: number = 50,
    query?: string
  ): Observable<GoogleDriveListResponse> {
    let params = new HttpParams().set('pageSize', pageSize.toString());

    if (folderId) {
      params = params.set('folderId', folderId);
    }
    if (pageToken) {
      params = params.set('pageToken', pageToken);
    }
    if (query) {
      params = params.set('query', query);
    }

    return this.http.get<GoogleDriveListResponse>(`${backendUrl}/google/drive/files`, { params });
  }

  /**
   * Search files in Google Drive
   */
  searchFiles(query: string, pageToken?: string): Observable<GoogleDriveListResponse> {
    let params = new HttpParams().set('query', query);

    if (pageToken) {
      params = params.set('pageToken', pageToken);
    }

    return this.http.get<GoogleDriveListResponse>(`${backendUrl}/google/drive/search`, { params });
  }

  /**
   * Get file metadata
   */
  getFile(fileId: string): Observable<GoogleDriveFile> {
    return this.http.get<GoogleDriveFile>(`${backendUrl}/google/drive/files/${fileId}`);
  }

  /**
   * Ingest selected files from Google Drive
   */
  ingestFiles(request: GoogleDriveIngestRequest): Observable<GoogleDriveIngestResponse> {
    return this.http.post<GoogleDriveIngestResponse>(`${backendUrl}/google/drive/ingest`, request);
  }

  /**
   * Get icon for file type
   */
  getFileIcon(mimeType: string): string {
    switch (mimeType) {
      case GOOGLE_WORKSPACE_MIME_TYPES.DOCUMENT:
        return 'description';
      case GOOGLE_WORKSPACE_MIME_TYPES.SPREADSHEET:
        return 'table_chart';
      case GOOGLE_WORKSPACE_MIME_TYPES.PRESENTATION:
        return 'slideshow';
      case GOOGLE_WORKSPACE_MIME_TYPES.DRAWING:
        return 'brush';
      case GOOGLE_WORKSPACE_MIME_TYPES.FORM:
        return 'assignment';
      case GOOGLE_WORKSPACE_MIME_TYPES.FOLDER:
        return 'folder';
      case GOOGLE_WORKSPACE_MIME_TYPES.PDF:
        return 'picture_as_pdf';
      case GOOGLE_WORKSPACE_MIME_TYPES.WORD:
        return 'description';
      case GOOGLE_WORKSPACE_MIME_TYPES.EXCEL:
        return 'table_chart';
      case GOOGLE_WORKSPACE_MIME_TYPES.POWERPOINT:
        return 'slideshow';
      case GOOGLE_WORKSPACE_MIME_TYPES.TEXT:
        return 'article';
      case GOOGLE_WORKSPACE_MIME_TYPES.CSV:
        return 'table_rows';
      default:
        if (mimeType.startsWith('image/')) return 'image';
        if (mimeType.startsWith('video/')) return 'movie';
        if (mimeType.startsWith('audio/')) return 'audio_file';
        return 'insert_drive_file';
    }
  }

  /**
   * Get icon class for file type
   */
  getFileIconClass(mimeType: string): string {
    switch (mimeType) {
      case GOOGLE_WORKSPACE_MIME_TYPES.DOCUMENT:
        return 'icon-google-doc';
      case GOOGLE_WORKSPACE_MIME_TYPES.SPREADSHEET:
        return 'icon-google-sheet';
      case GOOGLE_WORKSPACE_MIME_TYPES.PRESENTATION:
        return 'icon-google-slides';
      case GOOGLE_WORKSPACE_MIME_TYPES.FOLDER:
        return 'icon-folder';
      case GOOGLE_WORKSPACE_MIME_TYPES.PDF:
        return 'icon-pdf';
      default:
        return 'icon-default';
    }
  }

  /**
   * Get display name for MIME type
   */
  getMimeTypeDisplayName(mimeType: string): string {
    switch (mimeType) {
      case GOOGLE_WORKSPACE_MIME_TYPES.DOCUMENT:
        return 'Google Doc';
      case GOOGLE_WORKSPACE_MIME_TYPES.SPREADSHEET:
        return 'Google Sheet';
      case GOOGLE_WORKSPACE_MIME_TYPES.PRESENTATION:
        return 'Google Slides';
      case GOOGLE_WORKSPACE_MIME_TYPES.DRAWING:
        return 'Google Drawing';
      case GOOGLE_WORKSPACE_MIME_TYPES.FORM:
        return 'Google Form';
      case GOOGLE_WORKSPACE_MIME_TYPES.FOLDER:
        return 'Folder';
      case GOOGLE_WORKSPACE_MIME_TYPES.PDF:
        return 'PDF';
      case GOOGLE_WORKSPACE_MIME_TYPES.WORD:
        return 'Word Document';
      case GOOGLE_WORKSPACE_MIME_TYPES.EXCEL:
        return 'Excel Spreadsheet';
      case GOOGLE_WORKSPACE_MIME_TYPES.POWERPOINT:
        return 'PowerPoint';
      case GOOGLE_WORKSPACE_MIME_TYPES.TEXT:
        return 'Text File';
      case GOOGLE_WORKSPACE_MIME_TYPES.CSV:
        return 'CSV';
      default:
        return mimeType.split('/').pop() || 'File';
    }
  }

  /**
   * Check if file type is supported for indexing
   */
  isSupported(mimeType: string): boolean {
    const supportedTypes = [
      GOOGLE_WORKSPACE_MIME_TYPES.DOCUMENT,
      GOOGLE_WORKSPACE_MIME_TYPES.SPREADSHEET,
      GOOGLE_WORKSPACE_MIME_TYPES.PRESENTATION,
      GOOGLE_WORKSPACE_MIME_TYPES.PDF,
      GOOGLE_WORKSPACE_MIME_TYPES.WORD,
      GOOGLE_WORKSPACE_MIME_TYPES.EXCEL,
      GOOGLE_WORKSPACE_MIME_TYPES.POWERPOINT,
      GOOGLE_WORKSPACE_MIME_TYPES.TEXT,
      GOOGLE_WORKSPACE_MIME_TYPES.HTML,
      GOOGLE_WORKSPACE_MIME_TYPES.CSV,
      GOOGLE_WORKSPACE_MIME_TYPES.JSON,
      GOOGLE_WORKSPACE_MIME_TYPES.XML
    ];
    return supportedTypes.includes(mimeType) || mimeType.startsWith('text/');
  }

  /**
   * Check if item is a folder
   */
  isFolder(mimeType: string): boolean {
    return mimeType === GOOGLE_WORKSPACE_MIME_TYPES.FOLDER;
  }

  /**
   * Format file size
   */
  formatFileSize(bytes: number | string | undefined): string {
    if (!bytes) return '';
    const size = typeof bytes === 'string' ? parseInt(bytes, 10) : bytes;
    if (isNaN(size)) return '';
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
    if (size < 1024 * 1024 * 1024) return `${(size / (1024 * 1024)).toFixed(1)} MB`;
    return `${(size / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  }
}
