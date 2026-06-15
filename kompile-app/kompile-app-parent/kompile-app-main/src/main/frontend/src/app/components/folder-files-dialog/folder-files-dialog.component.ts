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

import { Component, Inject, OnInit, ViewChild, ElementRef } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialog } from '@angular/material/dialog';
import { filter } from 'rxjs/operators';
import { ChatFolder, FolderFile, ChatSessionDto } from '../../models/api-models';
import { FolderService } from '../../services/folder.service';
import { ChatHistoryService } from '../../services/chat-history.service';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';

export interface FolderFilesDialogData {
  folder: ChatFolder;
}

@Component({
  standalone: false,
  selector: 'app-folder-files-dialog',
  templateUrl: './folder-files-dialog.component.html',
  styleUrls: ['./folder-files-dialog.component.css']
})
export class FolderFilesDialogComponent implements OnInit {

  @ViewChild('fileInput') fileInput!: ElementRef<HTMLInputElement>;

  folder: ChatFolder;
  files: FolderFile[] = [];
  isLoading = false;
  isUploading = false;
  error: string | null = null;
  uploadProgress: { [fileName: string]: number } = {};
  isDragOver = false;
  hasChanges = false;

  // Tab state
  activeTab: 'files' | 'chats' = 'files';

  // Chats management
  folderSessions: ChatSessionDto[] = [];
  allSessions: ChatSessionDto[] = [];
  isLoadingSessions = false;
  showAddChatDialog = false;
  sessionSearchQuery = '';

  constructor(
    private dialogRef: MatDialogRef<FolderFilesDialogComponent>,
    @Inject(MAT_DIALOG_DATA) private data: FolderFilesDialogData,
    private folderService: FolderService,
    private chatHistoryService: ChatHistoryService,
    private dialog: MatDialog
  ) {
    this.folder = data.folder;
  }

  ngOnInit(): void {
    this.loadFiles();
    this.loadFolderSessions();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // TAB MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  switchTab(tab: 'files' | 'chats'): void {
    this.activeTab = tab;
    this.error = null;
  }

  loadFiles(): void {
    this.isLoading = true;
    this.error = null;

    this.folderService.getFolderFiles(this.folder.folderId).subscribe({
      next: (files) => {
        this.files = files;
        this.isLoading = false;
      },
      error: (err) => {
        this.error = err.message || 'Failed to load files';
        this.isLoading = false;
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FILE UPLOAD
  // ═══════════════════════════════════════════════════════════════════════════════

  triggerFileInput(): void {
    this.fileInput.nativeElement.click();
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.uploadFiles(Array.from(input.files));
      input.value = ''; // Reset input
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = true;
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;

    if (event.dataTransfer?.files && event.dataTransfer.files.length > 0) {
      this.uploadFiles(Array.from(event.dataTransfer.files));
    }
  }

  uploadFiles(files: File[]): void {
    if (files.length === 0) return;

    this.isUploading = true;
    this.error = null;

    if (files.length === 1) {
      this.uploadSingleFile(files[0]);
    } else {
      this.uploadMultipleFiles(files);
    }
  }

  private uploadSingleFile(file: File): void {
    this.uploadProgress[file.name] = 0;

    this.folderService.uploadFile(this.folder.folderId, file).subscribe({
      next: (uploadedFile) => {
        this.files.push(uploadedFile);
        delete this.uploadProgress[file.name];
        this.isUploading = false;
        this.hasChanges = true;
      },
      error: (err) => {
        this.error = `Failed to upload ${file.name}: ${err.message}`;
        delete this.uploadProgress[file.name];
        this.isUploading = false;
      }
    });
  }

  private uploadMultipleFiles(files: File[]): void {
    files.forEach(f => this.uploadProgress[f.name] = 0);

    this.folderService.uploadFiles(this.folder.folderId, files).subscribe({
      next: (response) => {
        this.files.push(...response.files);
        files.forEach(f => delete this.uploadProgress[f.name]);
        this.isUploading = false;
        this.hasChanges = true;

        if (response.errors && response.errors.length > 0) {
          this.error = `Some files failed: ${response.errors.join(', ')}`;
        }
      },
      error: (err) => {
        this.error = `Failed to upload files: ${err.message}`;
        files.forEach(f => delete this.uploadProgress[f.name]);
        this.isUploading = false;
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FILE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  deleteFile(file: FolderFile): void {
    const dialogData: ConfirmDialogData = {
      title: 'Delete File',
      message: `Delete "${file.fileName}"?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(filter(confirmed => confirmed === true))
      .subscribe(() => {
        this.folderService.deleteFile(this.folder.folderId, file.fileId).subscribe({
          next: () => {
            this.files = this.files.filter(f => f.fileId !== file.fileId);
            this.hasChanges = true;
          },
          error: (err) => {
            this.error = `Failed to delete ${file.fileName}: ${err.message}`;
          }
        });
      });
  }

  downloadFile(file: FolderFile): void {
    const url = this.folderService.getFileDownloadUrl(this.folder.folderId, file.fileId);
    window.open(url, '_blank');
  }

  copyPath(file: FolderFile): void {
    navigator.clipboard.writeText(file.storedPath).then(() => {
      // Show brief success feedback
    }).catch(err => {
      this.error = 'Failed to copy path to clipboard';
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // UTILITY
  // ═══════════════════════════════════════════════════════════════════════════════

  formatFileSize(bytes: number): string {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }

  formatDate(dateString: string): string {
    const date = new Date(dateString);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
  }

  getFileIcon(fileName: string): string {
    const ext = fileName.split('.').pop()?.toLowerCase() || '';
    const iconMap: { [key: string]: string } = {
      'pdf': 'picture_as_pdf',
      'doc': 'description',
      'docx': 'description',
      'txt': 'article',
      'md': 'article',
      'json': 'data_object',
      'xml': 'code',
      'csv': 'table_chart',
      'xls': 'table_chart',
      'xlsx': 'table_chart',
      'png': 'image',
      'jpg': 'image',
      'jpeg': 'image',
      'gif': 'image',
      'zip': 'folder_zip',
      'rar': 'folder_zip',
      '7z': 'folder_zip'
    };
    return iconMap[ext] || 'insert_drive_file';
  }

  trackByFileId(index: number, file: FolderFile): string {
    return file.fileId;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SESSION MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  loadFolderSessions(): void {
    this.isLoadingSessions = true;
    this.folderService.getFolderSessions(this.folder.folderId).subscribe({
      next: (sessions) => {
        this.folderSessions = sessions;
        this.isLoadingSessions = false;
      },
      error: (err) => {
        this.error = err.message || 'Failed to load folder sessions';
        this.isLoadingSessions = false;
      }
    });
  }

  loadAllSessions(): void {
    this.chatHistoryService.getSessions().subscribe({
      next: (sessions) => {
        // Filter out sessions already in this folder
        const folderSessionIds = new Set(this.folderSessions.map(s => s.sessionId));
        this.allSessions = sessions.filter(s => !folderSessionIds.has(s.sessionId));
      },
      error: (err) => {
        this.error = err.message || 'Failed to load sessions';
      }
    });
  }

  toggleAddChatDialog(): void {
    this.showAddChatDialog = !this.showAddChatDialog;
    if (this.showAddChatDialog) {
      this.loadAllSessions();
      this.sessionSearchQuery = '';
    }
  }

  getFilteredAvailableSessions(): ChatSessionDto[] {
    if (!this.sessionSearchQuery.trim()) {
      return this.allSessions;
    }
    const query = this.sessionSearchQuery.toLowerCase();
    return this.allSessions.filter(s =>
      (s.title && s.title.toLowerCase().includes(query)) ||
      (s.description && s.description.toLowerCase().includes(query))
    );
  }

  addSessionToFolder(session: ChatSessionDto): void {
    this.folderService.associateSession(this.folder.folderId, session.sessionId).subscribe({
      next: () => {
        // Move session from available to folder sessions
        this.allSessions = this.allSessions.filter(s => s.sessionId !== session.sessionId);
        this.folderSessions.push(session);
        this.hasChanges = true;
      },
      error: (err) => {
        this.error = `Failed to add chat to folder: ${err.message}`;
      }
    });
  }

  removeSessionFromFolder(session: ChatSessionDto): void {
    const dialogData: ConfirmDialogData = {
      title: 'Remove Chat',
      message: `Remove "${session.title}" from this folder?`,
      confirmText: 'Remove',
      confirmColor: 'warn',
      icon: 'remove_circle'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(filter(confirmed => confirmed === true))
      .subscribe(() => {
        this.folderService.disassociateSession(this.folder.folderId, session.sessionId).subscribe({
          next: () => {
            this.folderSessions = this.folderSessions.filter(s => s.sessionId !== session.sessionId);
            // Add back to available sessions if dialog is open
            if (this.showAddChatDialog) {
              this.allSessions.push(session);
            }
            this.hasChanges = true;
          },
          error: (err) => {
            this.error = `Failed to remove chat from folder: ${err.message}`;
          }
        });
      });
  }

  formatSessionDate(dateString: string): string {
    const date = new Date(dateString);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));

    if (diffDays === 0) {
      return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } else if (diffDays === 1) {
      return 'Yesterday';
    } else if (diffDays < 7) {
      return `${diffDays}d ago`;
    } else {
      return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
    }
  }

  trackBySessionId(index: number, session: ChatSessionDto): string {
    return session.sessionId;
  }

  close(): void {
    this.dialogRef.close({ updated: this.hasChanges });
  }
}
