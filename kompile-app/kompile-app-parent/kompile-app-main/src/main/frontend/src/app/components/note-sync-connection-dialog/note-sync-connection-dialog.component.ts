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

import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NoteSyncService } from '../../services/note-sync.service';
import {
  SyncConnectionRequest,
  SyncConnectionResponse,
  SyncProvider,
  SyncDirection,
  SyncAuthMode
} from '../../models/sync-models';

export interface ConnectionDialogData {
  factSheetId: number;
  mode: 'create' | 'edit';
  connection?: SyncConnectionResponse;
}

@Component({
  selector: 'app-note-sync-connection-dialog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTabsModule,
    MatCheckboxModule,
    MatDialogModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    MatTooltipModule
  ],
  templateUrl: './note-sync-connection-dialog.component.html',
  styleUrls: ['./note-sync-connection-dialog.component.css']
})
export class NoteSyncConnectionDialogComponent {
  provider: SyncProvider = 'NOTION';
  externalScope = '';
  direction: SyncDirection = 'BIDIRECTIONAL';
  pollCron = '';
  obsidianApiUrl = '';
  obsidianToken = '';
  authMode: SyncAuthMode = 'NONE';
  gitUsername = 'x-access-token';
  gitToken = '';
  authSecretConfigured = false;
  repositoryUrl = '';
  gitBranch = 'main';
  autoCommit = true;
  remoteSyncEnabled = true;
  isSaving = false;

  isEditMode: boolean;
  selectedTabIndex = 0;

  constructor(
    private dialogRef: MatDialogRef<NoteSyncConnectionDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: ConnectionDialogData,
    private noteSyncService: NoteSyncService,
    private snackBar: MatSnackBar
  ) {
    this.isEditMode = data.mode === 'edit';
    if (data.connection) {
      this.provider = data.connection.provider;
      this.externalScope = data.connection.externalScope;
      this.direction = data.connection.direction;
      this.pollCron = data.connection.pollCron || '';
      this.obsidianApiUrl = data.connection.obsidianApiUrl || '';
      this.authMode = data.connection.authMode || this.defaultAuthMode(data.connection.provider);
      this.gitUsername = data.connection.gitUsername || 'x-access-token';
      this.authSecretConfigured = data.connection.authSecretConfigured === true;
      this.repositoryUrl = data.connection.repositoryUrl || '';
      this.gitBranch = data.connection.gitBranch || 'main';
      this.autoCommit = data.connection.autoCommit !== false;
      this.remoteSyncEnabled = data.connection.remoteSyncEnabled !== false;
      this.selectedTabIndex = this.providerToTabIndex(this.provider);
    }
  }

  onTabChange(index: number): void {
    if (!this.isEditMode) {
      this.provider = this.tabIndexToProvider(index);
      this.authMode = this.defaultAuthMode(this.provider);
    }
  }

  private tabIndexToProvider(index: number): SyncProvider {
    switch (index) {
      case 1: return 'OBSIDIAN';
      case 2: return 'LOCAL_FOLDER';
      case 3: return 'GIT_REPOSITORY';
      default: return 'NOTION';
    }
  }

  private providerToTabIndex(provider: SyncProvider): number {
    switch (provider) {
      case 'OBSIDIAN': return 1;
      case 'LOCAL_FOLDER': return 2;
      case 'GIT_REPOSITORY': return 3;
      default: return 0;
    }
  }

  private defaultAuthMode(provider: SyncProvider): SyncAuthMode {
    if (provider === 'OBSIDIAN') return this.obsidianApiUrl.trim() ? 'OBSIDIAN_REST_TOKEN' : 'NONE';
    if (provider === 'GIT_REPOSITORY') return this.repositoryUrl.trim() ? 'SYSTEM_GIT' : 'NONE';
    return 'NONE';
  }

  authModeForSave(): SyncAuthMode {
    if (this.provider === 'OBSIDIAN') {
      return this.obsidianApiUrl.trim() ? 'OBSIDIAN_REST_TOKEN' : 'NONE';
    }
    if (this.provider === 'GIT_REPOSITORY') {
      if (!this.repositoryUrl.trim() || !this.remoteSyncEnabled) return 'NONE';
      return this.authMode === 'NONE' ? 'SYSTEM_GIT' : this.authMode;
    }
    return 'NONE';
  }

  gitRemoteAuthEnabled(): boolean {
    return this.provider === 'GIT_REPOSITORY' && !!this.repositoryUrl.trim() && this.remoteSyncEnabled;
  }

  save(): void {
    if (!this.externalScope.trim()) {
      this.snackBar.open('External scope is required', 'OK', { duration: 3000 });
      return;
    }
    if (this.provider === 'OBSIDIAN' && this.obsidianApiUrl.trim() && !this.obsidianToken.trim() && !this.authSecretConfigured) {
      this.snackBar.open('Obsidian REST API mode requires an API token', 'OK', { duration: 3000 });
      return;
    }
    if (this.provider === 'GIT_REPOSITORY' && this.gitRemoteAuthEnabled() && this.authMode === 'HTTPS_TOKEN'
      && !this.gitToken.trim() && !this.authSecretConfigured) {
      this.snackBar.open('HTTPS token auth requires a Git token', 'OK', { duration: 3000 });
      return;
    }

    const req: SyncConnectionRequest = {
      factSheetId: this.data.factSheetId,
      provider: this.provider,
      externalScope: this.externalScope.trim(),
      direction: this.direction,
      pollCron: this.pollCron.trim() || undefined,
      obsidianApiUrl: this.provider === 'OBSIDIAN' && this.obsidianApiUrl.trim()
        ? this.obsidianApiUrl.trim() : undefined,
      obsidianToken: this.provider === 'OBSIDIAN' && this.obsidianToken.trim()
        ? this.obsidianToken.trim() : undefined,
      authMode: this.authModeForSave(),
      gitUsername: this.provider === 'GIT_REPOSITORY' && this.authMode === 'HTTPS_TOKEN' && this.gitUsername.trim()
        ? this.gitUsername.trim() : undefined,
      gitToken: this.provider === 'GIT_REPOSITORY' && this.authMode === 'HTTPS_TOKEN' && this.gitToken.trim()
        ? this.gitToken.trim() : undefined,
      repositoryUrl: this.provider === 'GIT_REPOSITORY' && this.repositoryUrl.trim()
        ? this.repositoryUrl.trim() : undefined,
      gitBranch: this.provider === 'GIT_REPOSITORY' && this.gitBranch.trim()
        ? this.gitBranch.trim() : undefined,
      autoCommit: this.provider === 'GIT_REPOSITORY' ? this.autoCommit : undefined,
      remoteSyncEnabled: this.provider === 'GIT_REPOSITORY' ? this.remoteSyncEnabled : undefined
    };

    this.isSaving = true;

    const action = this.isEditMode
      ? this.noteSyncService.updateConnection(this.data.connection!.id, req)
      : this.noteSyncService.createConnection(req);

    action.subscribe({
      next: () => {
        this.isSaving = false;
        this.dialogRef.close(true);
      },
      error: err => {
        this.isSaving = false;
        this.snackBar.open(
          `Failed to ${this.isEditMode ? 'update' : 'create'} connection`,
          'Dismiss',
          { duration: 3000 }
        );
      }
    });
  }

  cancel(): void {
    this.dialogRef.close(false);
  }
}
