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
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError, BehaviorSubject } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import {
  ChatFolder,
  FolderFile,
  CreateFolderRequest,
  UpdateFolderRequest,
  FolderContext,
  BatchFileUploadResponse,
  ChatSessionDto
} from '../models/api-models';
import { BaseService } from './base.service';

/**
 * Service for managing folders and their files.
 * Folders allow users to organize chat sessions with associated reference files.
 */
@Injectable({
  providedIn: 'root'
})
export class FolderService extends BaseService {

  // Observable stream of folders for reactive updates
  private foldersSubject = new BehaviorSubject<ChatFolder[]>([]);
  public folders$ = this.foldersSubject.asObservable();

  // Selected folder for current session
  private selectedFolderSubject = new BehaviorSubject<ChatFolder | null>(null);
  public selectedFolder$ = this.selectedFolderSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FOLDER CRUD
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Create a new folder.
   */
  createFolder(request: CreateFolderRequest): Observable<ChatFolder> {
    return this.http.post<ChatFolder>(`${this.backendUrl}/folders`, request)
      .pipe(
        tap(folder => this.addFolderToCache(folder)),
        catchError(this.handleError)
      );
  }

  /**
   * Get all folders, optionally filtered by userId.
   */
  getFolders(userId?: string): Observable<ChatFolder[]> {
    const url = userId
      ? `${this.backendUrl}/folders?userId=${encodeURIComponent(userId)}`
      : `${this.backendUrl}/folders`;

    return this.http.get<ChatFolder[]>(url)
      .pipe(
        tap(folders => this.foldersSubject.next(folders)),
        catchError(this.handleError)
      );
  }

  /**
   * Get a specific folder with its files.
   */
  getFolder(folderId: string): Observable<ChatFolder> {
    return this.http.get<ChatFolder>(`${this.backendUrl}/folders/${folderId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Update a folder's name and/or description.
   */
  updateFolder(folderId: string, request: UpdateFolderRequest): Observable<ChatFolder> {
    return this.http.put<ChatFolder>(`${this.backendUrl}/folders/${folderId}`, request)
      .pipe(
        tap(folder => this.updateFolderInCache(folder)),
        catchError(this.handleError)
      );
  }

  /**
   * Delete a folder and all its files.
   */
  deleteFolder(folderId: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/folders/${folderId}`)
      .pipe(
        tap(() => this.removeFolderFromCache(folderId)),
        catchError(this.handleError)
      );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FILE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Upload a file to a folder.
   */
  uploadFile(folderId: string, file: File): Observable<FolderFile> {
    const formData = new FormData();
    formData.append('file', file, file.name);

    return this.http.post<FolderFile>(`${this.backendUrl}/folders/${folderId}/files`, formData)
      .pipe(catchError(this.handleError));
  }

  /**
   * Upload multiple files to a folder.
   */
  uploadFiles(folderId: string, files: File[]): Observable<BatchFileUploadResponse> {
    const formData = new FormData();
    files.forEach(file => formData.append('files', file, file.name));

    return this.http.post<BatchFileUploadResponse>(`${this.backendUrl}/folders/${folderId}/files/batch`, formData)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get all files in a folder.
   */
  getFolderFiles(folderId: string): Observable<FolderFile[]> {
    return this.http.get<FolderFile[]>(`${this.backendUrl}/folders/${folderId}/files`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Delete a file from a folder.
   */
  deleteFile(folderId: string, fileId: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/folders/${folderId}/files/${fileId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get download URL for a file.
   */
  getFileDownloadUrl(folderId: string, fileId: string): string {
    return `${this.backendUrl}/folders/${folderId}/files/${fileId}/download`;
  }

  /**
   * Download a file (returns blob).
   */
  downloadFile(folderId: string, fileId: string): Observable<Blob> {
    return this.http.get(`${this.backendUrl}/folders/${folderId}/files/${fileId}/download`, {
      responseType: 'blob'
    }).pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SESSION ASSOCIATION
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Associate a session with a folder.
   */
  associateSession(folderId: string, sessionId: string): Observable<ChatSessionDto> {
    return this.http.post<ChatSessionDto>(`${this.backendUrl}/folders/${folderId}/sessions/${sessionId}`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Disassociate a session from a folder.
   */
  disassociateSession(folderId: string, sessionId: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/folders/${folderId}/sessions/${sessionId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get all sessions associated with a folder.
   */
  getFolderSessions(folderId: string): Observable<ChatSessionDto[]> {
    return this.http.get<ChatSessionDto[]>(`${this.backendUrl}/folders/${folderId}/sessions`)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FOLDER CONTEXT
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get folder context for prompt injection.
   */
  getFolderContext(folderId: string): Observable<FolderContext> {
    return this.http.get<FolderContext>(`${this.backendUrl}/folders/${folderId}/context`)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // STATE MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get current folders from cache.
   */
  getCachedFolders(): ChatFolder[] {
    return this.foldersSubject.value;
  }

  /**
   * Select a folder for the current session.
   */
  selectFolder(folder: ChatFolder | null): void {
    this.selectedFolderSubject.next(folder);
  }

  /**
   * Get currently selected folder.
   */
  getSelectedFolder(): ChatFolder | null {
    return this.selectedFolderSubject.value;
  }

  /**
   * Clear folder selection.
   */
  clearSelection(): void {
    this.selectedFolderSubject.next(null);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // PRIVATE HELPERS
  // ═══════════════════════════════════════════════════════════════════════════════

  private addFolderToCache(folder: ChatFolder): void {
    const current = this.foldersSubject.value;
    this.foldersSubject.next([folder, ...current]);
  }

  private updateFolderInCache(folder: ChatFolder): void {
    const current = this.foldersSubject.value;
    const updated = current.map(f => f.folderId === folder.folderId ? folder : f);
    this.foldersSubject.next(updated);
  }

  private removeFolderFromCache(folderId: string): void {
    const current = this.foldersSubject.value;
    this.foldersSubject.next(current.filter(f => f.folderId !== folderId));

    // Clear selection if deleted folder was selected
    if (this.selectedFolderSubject.value?.folderId === folderId) {
      this.selectedFolderSubject.next(null);
    }
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'An error occurred';

    if (error.error instanceof ErrorEvent) {
      // Client-side error
      errorMessage = error.error.message;
    } else {
      // Server-side error
      if (typeof error.error === 'string') {
        errorMessage = error.error;
      } else if (error.error?.message) {
        errorMessage = error.error.message;
      } else {
        errorMessage = `Error ${error.status}: ${error.statusText}`;
      }
    }

    console.error('FolderService error:', errorMessage, error);
    return throwError(() => new Error(errorMessage));
  }
}
