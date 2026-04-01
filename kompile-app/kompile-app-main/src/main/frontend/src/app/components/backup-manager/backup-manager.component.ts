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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subject, interval } from 'rxjs';
import { takeUntil, filter } from 'rxjs/operators';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';

import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatListModule } from '@angular/material/list';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';

import { BackupService } from '../../services/backup.service';
import { BackupStatus, BackupInfo, BackupResult } from '../../models/api-models';

@Component({
  selector: 'app-backup-manager',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatDividerModule,
    MatListModule,
    MatChipsModule,
    MatTooltipModule,
    MatDialogModule
  ],
  templateUrl: './backup-manager.component.html',
  styleUrls: ['./backup-manager.component.css']
})
export class BackupManagerComponent implements OnInit, OnDestroy {

  private destroy$ = new Subject<void>();

  // State
  status: BackupStatus | null = null;
  backups: BackupInfo[] = [];
  loading = true;
  operationInProgress = false;
  totalSizeMB = 0;

  // Auto-refresh interval (30 seconds)
  private readonly REFRESH_INTERVAL = 30000;

  constructor(
    private backupService: BackupService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadData();

    // Subscribe to status updates
    this.backupService.status$
      .pipe(takeUntil(this.destroy$))
      .subscribe(status => {
        this.status = status;
      });

    // Subscribe to backup list updates
    this.backupService.backups$
      .pipe(takeUntil(this.destroy$))
      .subscribe(backups => {
        this.backups = backups;
      });

    // Auto-refresh status periodically
    interval(this.REFRESH_INTERVAL)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        if (!this.operationInProgress) {
          this.refreshStatus();
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // DATA LOADING
  // ═══════════════════════════════════════════════════════════════════════════════

  loadData(): void {
    this.loading = true;

    // Load status and backups in parallel
    this.backupService.getStatus().subscribe({
      next: () => {},
      error: (err) => this.showError('Failed to load backup status: ' + err.message)
    });

    this.backupService.listBackups().subscribe({
      next: (response) => {
        this.totalSizeMB = response.totalSizeMB;
        this.loading = false;
      },
      error: (err) => {
        this.showError('Failed to load backups: ' + err.message);
        this.loading = false;
      }
    });
  }

  refreshStatus(): void {
    this.backupService.getStatus().subscribe({
      error: (err) => console.error('Failed to refresh status:', err)
    });
  }

  refresh(): void {
    this.loadData();
    this.showSuccess('Refreshed');
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // BACKUP OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  triggerBackup(): void {
    if (this.operationInProgress || this.status?.inProgress) {
      this.showError('A backup is already in progress');
      return;
    }

    this.operationInProgress = true;
    this.showInfo('Starting backup...');

    this.backupService.triggerBackup().subscribe({
      next: (result: BackupResult) => {
        this.operationInProgress = false;
        if (result.success) {
          this.showSuccess(`Backup completed: ${result.fileCount} files, ${this.formatSize(result.totalMB)} in ${this.formatDuration(result.durationMs)}`);
        } else {
          this.showError('Backup completed with errors: ' + result.message);
        }
        this.loadData();
      },
      error: (err) => {
        this.operationInProgress = false;
        this.showError('Backup failed: ' + err.message);
      }
    });
  }

  cleanup(): void {
    if (this.operationInProgress) {
      return;
    }

    this.operationInProgress = true;
    this.backupService.cleanup().subscribe({
      next: (response) => {
        this.operationInProgress = false;
        if (response.deletedCount > 0) {
          this.showSuccess(`Cleaned up ${response.deletedCount} old backup(s)`);
        } else {
          this.showInfo('No old backups to clean up');
        }
      },
      error: (err) => {
        this.operationInProgress = false;
        this.showError('Cleanup failed: ' + err.message);
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // BACKUP ITEM ACTIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  downloadBackup(backup: BackupInfo): void {
    if (backup.format !== 'COMPRESSED') {
      this.showError('Only compressed backups can be downloaded');
      return;
    }
    this.backupService.downloadBackup(backup.name);
    this.showInfo('Download started...');
  }

  restoreBackup(backup: BackupInfo): void {
    if (this.operationInProgress || this.status?.inProgress) {
      this.showError('Cannot restore while another operation is in progress');
      return;
    }

    const dialogData: ConfirmDialogData = {
      title: 'Restore Backup',
      message: `Are you sure you want to restore from "${backup.name}"?\n\nThis will overwrite current databases and indexes. The application may need to be restarted after restoration.`,
      confirmText: 'Restore',
      confirmColor: 'warn',
      icon: 'restore'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(filter(confirmed => confirmed === true))
      .subscribe(() => {
        this.operationInProgress = true;
        this.showInfo('Restoring backup...');

        this.backupService.restoreBackup(backup.name).subscribe({
          next: (result) => {
            this.operationInProgress = false;
            if (result.success) {
              this.showSuccess(`Restore completed in ${this.formatDuration(result.durationMs)}. Please restart the application for changes to take effect.`);
            } else {
              this.showError('Restore failed: ' + result.message);
            }
          },
          error: (err) => {
            this.operationInProgress = false;
            this.showError('Restore failed: ' + err.message);
          }
        });
      });
  }

  deleteBackup(backup: BackupInfo): void {
    if (this.operationInProgress) {
      return;
    }

    const dialogData: ConfirmDialogData = {
      title: 'Delete Backup',
      message: `Are you sure you want to delete "${backup.name}"?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(filter(confirmed => confirmed === true))
      .subscribe(() => {
        this.operationInProgress = true;
        this.backupService.deleteBackup(backup.name).subscribe({
          next: () => {
            this.operationInProgress = false;
            this.showSuccess('Backup deleted');
            this.loadData();
          },
          error: (err) => {
            this.operationInProgress = false;
            this.showError('Delete failed: ' + err.message);
          }
        });
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FORMATTERS
  // ═══════════════════════════════════════════════════════════════════════════════

  formatSize(sizeMB: number): string {
    return this.backupService.formatSize(sizeMB);
  }

  formatDuration(durationMs: number): string {
    return this.backupService.formatDuration(durationMs);
  }

  formatDate(dateString: string): string {
    const date = new Date(dateString);
    return date.toLocaleString();
  }

  formatInterval(hours: number): string {
    if (hours < 1) {
      return `${Math.round(hours * 60)} minutes`;
    } else if (hours === 1) {
      return '1 hour';
    } else if (hours < 24) {
      return `${hours} hours`;
    } else if (hours === 24) {
      return '1 day';
    } else {
      return `${Math.round(hours / 24)} days`;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // NOTIFICATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  private showSuccess(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 5000,
      panelClass: ['success-snackbar']
    });
  }

  private showError(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 8000,
      panelClass: ['error-snackbar']
    });
  }

  private showInfo(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 3000
    });
  }
}
