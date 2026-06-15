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
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSliderModule } from '@angular/material/slider';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDialogModule } from '@angular/material/dialog';
import { Subject, takeUntil } from 'rxjs';

import {
  LogConfigService,
  LogConfiguration,
  LogStatus,
  LogConfigResponse,
  ArchiveInfo,
  ArchiveListResponse
} from '../../../services/log-config.service';

@Component({
  selector: 'app-log-settings',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    MatSliderModule,
    MatCardModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatDialogModule
  ],
  templateUrl: './log-settings.component.html',
  styleUrls: ['./log-settings.component.scss']
})
export class LogSettingsComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  // Configuration
  config: LogConfiguration = {
    enabled: true,
    retentionDays: 7,
    maxEntriesPerJob: 10000,
    maxTotalEntries: 500000,
    archiveEnabled: false,
    archivePath: '',
    archiveOnCleanup: false
  };

  // Status
  status: LogStatus | null = null;

  // State
  loading = false;
  saving = false;
  available = false;
  error: string | null = null;
  successMessage: string | null = null;

  // Cleanup dialog
  showCleanupDialog = false;
  cleanupHours = 168; // 7 days
  cleanupLoading = false;

  // Archive state
  archives: ArchiveInfo[] = [];
  archiveLoading = false;
  archiveCreating = false;
  showArchiveDeleteDialog = false;
  archiveToDelete: ArchiveInfo | null = null;
  archiveDeleting = false;

  constructor(private logConfigService: LogConfigService) { }

  ngOnInit(): void {
    this.loadConfiguration();
    this.loadArchives();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadConfiguration(): void {
    this.loading = true;
    this.error = null;

    this.logConfigService.getConfiguration()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response: LogConfigResponse) => {
          this.available = response.available;
          if (response.config) {
            this.config = response.config;
          }
          if (response.status) {
            this.status = response.status;
          }
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load log configuration: ' + (err.error?.error || err.message);
          this.loading = false;
        }
      });
  }

  saveConfiguration(): void {
    this.saving = true;
    this.error = null;
    this.successMessage = null;

    this.logConfigService.updateConfiguration(this.config)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response: LogConfigResponse) => {
          if (response.config) {
            this.config = response.config;
          }
          if (response.status) {
            this.status = response.status;
          }
          this.successMessage = 'Configuration saved successfully';
          this.saving = false;

          // Clear success message after 3 seconds
          setTimeout(() => {
            this.successMessage = null;
          }, 3000);
        },
        error: (err) => {
          this.error = 'Failed to save configuration: ' + (err.error?.error || err.message);
          this.saving = false;
        }
      });
  }

  toggleEnabled(): void {
    const action = this.config.enabled ?
      this.logConfigService.enable() :
      this.logConfigService.disable();

    this.saving = true;
    action.pipe(takeUntil(this.destroy$)).subscribe({
      next: (response) => {
        this.config.enabled = response.enabled;
        this.successMessage = response.message;
        this.saving = false;

        setTimeout(() => {
          this.successMessage = null;
        }, 3000);
      },
      error: (err) => {
        // Revert toggle on error
        this.config.enabled = !this.config.enabled;
        this.error = 'Failed to toggle logging: ' + (err.error?.error || err.message);
        this.saving = false;
      }
    });
  }

  openCleanupDialog(): void {
    this.showCleanupDialog = true;
  }

  closeCleanupDialog(): void {
    this.showCleanupDialog = false;
  }

  triggerCleanup(): void {
    this.cleanupLoading = true;

    this.logConfigService.triggerCleanup(this.cleanupHours)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.status = response.statusAfter;
          this.successMessage = `Cleaned up ${response.deletedCount} log entries`;
          this.cleanupLoading = false;
          this.closeCleanupDialog();

          setTimeout(() => {
            this.successMessage = null;
          }, 5000);
        },
        error: (err) => {
          this.error = 'Failed to cleanup logs: ' + (err.error?.error || err.message);
          this.cleanupLoading = false;
        }
      });
  }

  refreshStatus(): void {
    this.logConfigService.getStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.status = status;
        },
        error: (err) => {
          console.error('Failed to refresh status:', err);
        }
      });
  }

  formatCount(count: number): string {
    return this.logConfigService.formatCount(count);
  }

  getUtilizationClass(percent: number): string {
    return this.logConfigService.getUtilizationClass(percent);
  }

  getUtilizationColor(percent: number): 'primary' | 'accent' | 'warn' {
    if (percent < 50) return 'primary';
    if (percent < 80) return 'accent';
    return 'warn';
  }

  // ========== Archive Methods ==========

  loadArchives(): void {
    this.archiveLoading = true;

    this.logConfigService.listArchives()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response: ArchiveListResponse) => {
          this.archives = response.archives || [];
          // Update config archive settings from response
          if (response.archiveEnabled !== undefined) {
            this.config.archiveEnabled = response.archiveEnabled;
          }
          if (response.archivePath) {
            this.config.archivePath = response.archivePath;
          }
          if (response.archiveOnCleanup !== undefined) {
            this.config.archiveOnCleanup = response.archiveOnCleanup;
          }
          this.archiveLoading = false;
        },
        error: (err) => {
          console.error('Failed to load archives:', err);
          this.archiveLoading = false;
        }
      });
  }

  createArchive(): void {
    this.archiveCreating = true;
    this.error = null;

    this.logConfigService.createArchive()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.success) {
            this.successMessage = response.message || 'Archive created successfully';
            this.loadArchives(); // Refresh archive list
          } else {
            this.successMessage = response.message || 'No logs to archive';
          }
          this.archiveCreating = false;

          setTimeout(() => {
            this.successMessage = null;
          }, 5000);
        },
        error: (err) => {
          this.error = 'Failed to create archive: ' + (err.error?.error || err.message);
          this.archiveCreating = false;
        }
      });
  }

  downloadArchive(archive: ArchiveInfo): void {
    const url = this.logConfigService.getArchiveDownloadUrl(archive.fileName);
    // Open download in new window/tab
    window.open(url, '_blank');
  }

  openArchiveDeleteDialog(archive: ArchiveInfo): void {
    this.archiveToDelete = archive;
    this.showArchiveDeleteDialog = true;
  }

  closeArchiveDeleteDialog(): void {
    this.showArchiveDeleteDialog = false;
    this.archiveToDelete = null;
  }

  confirmDeleteArchive(): void {
    if (!this.archiveToDelete) return;

    this.archiveDeleting = true;

    this.logConfigService.deleteArchive(this.archiveToDelete.fileName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.success) {
            this.successMessage = response.message || 'Archive deleted successfully';
            this.loadArchives(); // Refresh archive list
          }
          this.archiveDeleting = false;
          this.closeArchiveDeleteDialog();

          setTimeout(() => {
            this.successMessage = null;
          }, 3000);
        },
        error: (err) => {
          this.error = 'Failed to delete archive: ' + (err.error?.error || err.message);
          this.archiveDeleting = false;
          this.closeArchiveDeleteDialog();
        }
      });
  }

  formatFileSize(bytes: number): string {
    return this.logConfigService.formatFileSize(bytes);
  }

  formatArchiveDate(dateStr: string): string {
    if (!dateStr) return 'Unknown';
    try {
      const date = new Date(dateStr);
      return date.toLocaleString();
    } catch {
      return dateStr;
    }
  }
}
