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

import { Component, OnInit, OnDestroy, Output, EventEmitter } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { takeUntil, filter } from 'rxjs/operators';
import { ChatFolder, CreateFolderRequest } from '../../models/api-models';
import { FolderService } from '../../services/folder.service';
import { FolderFilesDialogComponent } from '../folder-files-dialog/folder-files-dialog.component';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';

@Component({
  standalone: false,
  selector: 'app-folder-sidebar',
  templateUrl: './folder-sidebar.component.html',
  styleUrls: ['./folder-sidebar.component.css']
})
export class FolderSidebarComponent implements OnInit, OnDestroy {

  @Output() folderSelected = new EventEmitter<ChatFolder | null>();

  folders: ChatFolder[] = [];
  selectedFolder: ChatFolder | null = null;
  isLoading = false;
  error: string | null = null;

  // New folder form
  showNewFolderForm = false;
  newFolderName = '';
  newFolderDescription = '';
  isCreating = false;

  // Edit mode
  editingFolder: ChatFolder | null = null;
  editName = '';
  editDescription = '';

  private destroy$ = new Subject<void>();

  constructor(
    private folderService: FolderService,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadFolders();

    // Subscribe to folder changes
    this.folderService.folders$
      .pipe(takeUntil(this.destroy$))
      .subscribe(folders => {
        this.folders = folders;
      });

    // Subscribe to selected folder changes
    this.folderService.selectedFolder$
      .pipe(takeUntil(this.destroy$))
      .subscribe(folder => {
        this.selectedFolder = folder;
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadFolders(): void {
    this.isLoading = true;
    this.error = null;

    this.folderService.getFolders().subscribe({
      next: (folders) => {
        this.folders = folders;
        this.isLoading = false;
      },
      error: (err) => {
        this.error = err.message || 'Failed to load folders';
        this.isLoading = false;
      }
    });
  }

  selectFolder(folder: ChatFolder): void {
    if (this.selectedFolder?.folderId === folder.folderId) {
      // Deselect if clicking the same folder
      this.selectedFolder = null;
      this.folderService.selectFolder(null);
      this.folderSelected.emit(null);
    } else {
      this.selectedFolder = folder;
      this.folderService.selectFolder(folder);
      this.folderSelected.emit(folder);
    }
  }

  clearSelection(): void {
    this.selectedFolder = null;
    this.folderService.clearSelection();
    this.folderSelected.emit(null);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CREATE FOLDER
  // ═══════════════════════════════════════════════════════════════════════════════

  showCreateForm(): void {
    this.showNewFolderForm = true;
    this.newFolderName = '';
    this.newFolderDescription = '';
  }

  cancelCreate(): void {
    this.showNewFolderForm = false;
    this.newFolderName = '';
    this.newFolderDescription = '';
  }

  createFolder(): void {
    if (!this.newFolderName.trim()) {
      return;
    }

    this.isCreating = true;
    const request: CreateFolderRequest = {
      name: this.newFolderName.trim(),
      description: this.newFolderDescription.trim() || undefined
    };

    this.folderService.createFolder(request).subscribe({
      next: (folder) => {
        this.showNewFolderForm = false;
        this.newFolderName = '';
        this.newFolderDescription = '';
        this.isCreating = false;
        // Auto-select the new folder
        this.selectFolder(folder);
      },
      error: (err) => {
        this.error = err.message || 'Failed to create folder';
        this.isCreating = false;
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // EDIT FOLDER
  // ═══════════════════════════════════════════════════════════════════════════════

  startEdit(folder: ChatFolder, event: Event): void {
    event.stopPropagation();
    this.editingFolder = folder;
    this.editName = folder.name;
    this.editDescription = folder.description || '';
  }

  cancelEdit(): void {
    this.editingFolder = null;
    this.editName = '';
    this.editDescription = '';
  }

  saveEdit(): void {
    if (!this.editingFolder || !this.editName.trim()) {
      return;
    }

    this.folderService.updateFolder(this.editingFolder.folderId, {
      name: this.editName.trim(),
      description: this.editDescription.trim() || undefined
    }).subscribe({
      next: () => {
        this.editingFolder = null;
        this.editName = '';
        this.editDescription = '';
      },
      error: (err) => {
        this.error = err.message || 'Failed to update folder';
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // DELETE FOLDER
  // ═══════════════════════════════════════════════════════════════════════════════

  deleteFolder(folder: ChatFolder, event: Event): void {
    event.stopPropagation();

    const dialogData: ConfirmDialogData = {
      title: 'Delete Folder',
      message: `Delete folder "${folder.name}"? All files in this folder will be permanently deleted.`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'folder_delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.folderService.deleteFolder(folder.folderId).subscribe({
          next: () => {
            if (this.selectedFolder?.folderId === folder.folderId) {
              this.clearSelection();
            }
          },
          error: (err) => {
            this.error = err.message || 'Failed to delete folder';
          }
        });
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FILES DIALOG
  // ═══════════════════════════════════════════════════════════════════════════════

  openFilesDialog(folder: ChatFolder, event: Event): void {
    event.stopPropagation();

    const dialogRef = this.dialog.open(FolderFilesDialogComponent, {
      width: '700px',
      maxHeight: '80vh',
      data: { folder }
    });

    dialogRef.afterClosed().subscribe(result => {
      if (result?.updated) {
        // Refresh the folder to get updated file count
        this.loadFolders();
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // UTILITY
  // ═══════════════════════════════════════════════════════════════════════════════

  formatDate(dateString: string): string {
    const date = new Date(dateString);
    return date.toLocaleDateString();
  }

  trackByFolderId(index: number, folder: ChatFolder): string {
    return folder.folderId;
  }
}
