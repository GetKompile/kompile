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

import { Component, Input, OnChanges, OnDestroy, OnInit, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatCardModule } from '@angular/material/card';
import { MatMenuModule } from '@angular/material/menu';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatTableModule } from '@angular/material/table';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { NoteSyncService } from '../../services/note-sync.service';
import {
  SyncConnectionResponse,
  SyncRecord,
  NoteSyncConfig,
  SyncProvider,
  SyncDirection
} from '../../models/sync-models';
import { NoteSyncConnectionDialogComponent } from '../note-sync-connection-dialog/note-sync-connection-dialog.component';
import { WebSocketService } from '../../services/websocket.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-note-sync-manager',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    MatProgressBarModule,
    MatSnackBarModule,
    MatDividerModule,
    MatCardModule,
    MatMenuModule,
    MatSlideToggleModule,
    MatExpansionModule,
    MatDialogModule,
    MatTableModule
  ],
  templateUrl: './note-sync-manager.component.html',
  styleUrls: ['./note-sync-manager.component.css']
})
export class NoteSyncManagerComponent implements OnChanges, OnDestroy, OnInit {
  @Input() factSheetId: number | null | undefined = null;

  connections: SyncConnectionResponse[] = [];
  config: NoteSyncConfig | null = null;
  isLoading = false;
  syncingConnections = new Set<number>();
  testingAuthConnections = new Set<number>();

  // Expanded connection for viewing records
  expandedConnectionId: number | null = null;
  syncRecords: SyncRecord[] = [];
  isLoadingRecords = false;

  // Live sync progress from WebSocket
  liveSyncStatus: any = null;
  private wsSub: Subscription | null = null;

  private destroy$ = new Subject<void>();

  constructor(
    private noteSyncService: NoteSyncService,
    private dialog: MatDialog,
    private snackBar: MatSnackBar,
    private wsService: WebSocketService
  ) {}

  ngOnInit(): void {
    this.wsSub = this.wsService.subscribeToSyncProgress().subscribe((update: any) => {
      this.liveSyncStatus = update;
      if (update.status === 'COMPLETED' || update.status === 'ERROR') {
        this.loadConnections();
        setTimeout(() => this.liveSyncStatus = null, 5000);
      }
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && this.factSheetId) {
      this.loadConnections();
      this.loadConfig();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    if (this.wsSub) this.wsSub.unsubscribe();
    this.wsService.unsubscribeFromSyncProgress();
  }

  loadConfig(): void {
    this.noteSyncService.getConfig().pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: config => this.config = config,
      error: () => {} // Config may not exist yet
    });
  }

  loadConnections(): void {
    if (!this.factSheetId) return;
    this.isLoading = true;
    this.noteSyncService.loadConnections(this.factSheetId).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: conns => {
        this.connections = conns;
        this.isLoading = false;
      },
      error: () => {
        this.snackBar.open('Failed to load sync connections', 'Dismiss', { duration: 3000 });
        this.isLoading = false;
      }
    });
  }

  openCreateDialog(): void {
    const dialogRef = this.dialog.open(NoteSyncConnectionDialogComponent, {
      width: '560px',
      data: { factSheetId: this.factSheetId, mode: 'create' }
    });

    dialogRef.afterClosed().pipe(takeUntil(this.destroy$)).subscribe(result => {
      if (result) this.loadConnections();
    });
  }

  openEditDialog(conn: SyncConnectionResponse): void {
    const dialogRef = this.dialog.open(NoteSyncConnectionDialogComponent, {
      width: '560px',
      data: { factSheetId: this.factSheetId, mode: 'edit', connection: conn }
    });

    dialogRef.afterClosed().pipe(takeUntil(this.destroy$)).subscribe(result => {
      if (result) this.loadConnections();
    });
  }

  triggerSync(conn: SyncConnectionResponse): void {
    this.syncingConnections.add(conn.id);
    this.noteSyncService.triggerSync(conn.id).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: result => {
        this.snackBar.open(`Sync started (session: ${result.sessionId})`, 'OK', { duration: 3000 });
        // Reload after a brief delay to get updated status
        setTimeout(() => {
          this.syncingConnections.delete(conn.id);
          this.loadConnections();
        }, 3000);
      },
      error: () => {
        this.syncingConnections.delete(conn.id);
        this.snackBar.open('Failed to trigger sync', 'Dismiss', { duration: 3000 });
      }
    });
  }

  toggleConnection(conn: SyncConnectionResponse): void {
    const action = conn.enabled
      ? this.noteSyncService.disableConnection(conn.id)
      : this.noteSyncService.enableConnection(conn.id);

    action.pipe(takeUntil(this.destroy$)).subscribe({
      next: () => this.loadConnections(),
      error: () => this.snackBar.open('Failed to toggle connection', 'Dismiss', { duration: 3000 })
    });
  }

  testAuth(conn: SyncConnectionResponse): void {
    this.testingAuthConnections.add(conn.id);
    this.noteSyncService.testConnectionAuth(conn.id).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: result => {
        this.testingAuthConnections.delete(conn.id);
        this.snackBar.open(result.message, 'OK', { duration: result.success ? 3500 : 6000 });
        this.loadConnections();
      },
      error: () => {
        this.testingAuthConnections.delete(conn.id);
        this.snackBar.open('Failed to test connection auth', 'Dismiss', { duration: 3000 });
      }
    });
  }

  isTestingAuth(connId: number): boolean {
    return this.testingAuthConnections.has(connId);
  }

  deleteConnection(conn: SyncConnectionResponse): void {
    if (!confirm(`Delete sync connection to ${conn.provider} (${conn.externalScope})?`)) return;
    this.noteSyncService.deleteConnection(conn.id).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: () => {
        this.connections = this.connections.filter(c => c.id !== conn.id);
        this.snackBar.open('Connection deleted', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Failed to delete connection', 'Dismiss', { duration: 3000 })
    });
  }

  toggleRecords(conn: SyncConnectionResponse): void {
    if (this.expandedConnectionId === conn.id) {
      this.expandedConnectionId = null;
      this.syncRecords = [];
      return;
    }
    this.expandedConnectionId = conn.id;
    this.loadRecords(conn.id);
  }

  loadRecords(connectionId: number): void {
    this.isLoadingRecords = true;
    this.noteSyncService.listRecords(connectionId).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: records => {
        this.syncRecords = records;
        this.isLoadingRecords = false;
      },
      error: () => {
        this.isLoadingRecords = false;
      }
    });
  }

  resolveConflict(connectionId: number, recordId: number, resolution: string): void {
    this.noteSyncService.resolveConflict(connectionId, recordId, resolution).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: () => {
        this.snackBar.open('Conflict resolved', 'OK', { duration: 2000 });
        this.loadRecords(connectionId);
      },
      error: () => this.snackBar.open('Failed to resolve conflict', 'Dismiss', { duration: 3000 })
    });
  }

  isSyncing(connId: number): boolean {
    return this.syncingConnections.has(connId);
  }

  getProviderIcon(provider: SyncProvider): string {
    switch (provider) {
      case 'NOTION': return 'cloud';
      case 'GIT_REPOSITORY': return 'hub';
      case 'LOCAL_FOLDER': return 'folder_open';
      default: return 'folder';
    }
  }

  getDirectionLabel(dir: SyncDirection): string {
    switch (dir) {
      case 'BIDIRECTIONAL': return 'Bidirectional';
      case 'KOMPILE_TO_EXTERNAL': return 'Push only';
      case 'EXTERNAL_TO_KOMPILE': return 'Pull only';
    }
  }

  getStatusColor(status: string): string {
    switch (status) {
      case 'OK': return 'primary';
      case 'ERROR': return 'warn';
      case 'CONFLICT': return 'accent';
      default: return '';
    }
  }

  getStatusIcon(status: string): string {
    switch (status) {
      case 'OK': return 'check_circle';
      case 'ERROR': return 'error';
      case 'CONFLICT': return 'warning';
      case 'NEVER': return 'schedule';
      default: return 'help';
    }
  }

  getAuthModeLabel(conn: SyncConnectionResponse): string {
    switch (conn.authMode) {
      case 'OBSIDIAN_REST_TOKEN': return 'Obsidian token';
      case 'SYSTEM_GIT': return 'System Git';
      case 'HTTPS_TOKEN': return 'HTTPS token';
      case 'NONE': return 'No auth';
      default: return 'Auth unknown';
    }
  }

  getAuthStatusIcon(status: string | undefined): string {
    switch (status) {
      case 'VALID': return 'verified';
      case 'CONFIGURED': return 'lock';
      case 'NOT_REQUIRED': return 'lock_open';
      case 'MISSING': return 'key_off';
      case 'INVALID': return 'error';
      default: return 'help';
    }
  }

  getAuthStatusClass(status: string | undefined): string {
    return `auth-${(status || 'unknown').toLowerCase()}`;
  }

  formatDate(dateStr: string | undefined): string {
    if (!dateStr) return 'Never';
    return new Date(dateStr).toLocaleString();
  }
}
