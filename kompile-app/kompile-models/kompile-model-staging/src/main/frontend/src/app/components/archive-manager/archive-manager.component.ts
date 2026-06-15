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
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { ArchiveService } from '../../services/archive.service';
import { StagingService } from '../../services/staging.service';
import {
  ArchiveInstallInfo,
  ArchiveExportRequest,
  ArchiveImportRequest,
  ArchiveDownloadRequest,
  ArchiveUpdateResponse,
  UpdateInfo,
  RemoteCatalog,
  RemoteCatalogEntry,
  ModelRegistry
} from '../../models/api-models';

@Component({
  selector: 'app-archive-manager',
  standalone: false,
  templateUrl: './archive-manager.component.html',
  styleUrls: ['./archive-manager.component.css']
})
export class ArchiveManagerComponent implements OnInit, OnDestroy {

  private destroy$ = new Subject<void>();

  // Tab index
  selectedTabIndex = 0;

  // Installed archives
  installedArchives: ArchiveInstallInfo[] = [];
  loadingArchives = false;

  // Remote catalog
  remoteCatalog: RemoteCatalog | null = null;
  loadingCatalog = false;

  // Updates
  updateInfo: ArchiveUpdateResponse | null = null;
  loadingUpdates = false;
  applyingUpdate = false;
  applyingAllUpdates = false;

  // Export
  availableModels: string[] = [];
  exportForm: ArchiveExportRequest = {
    modelIds: [],
    archiveId: 'kompile-models',
    version: '1.0.0',
    exportAll: false
  };
  exporting = false;

  // Import
  importForm: ArchiveImportRequest = {
    archivePath: '',
    verifyChecksums: true,
    forceOverwrite: false
  };
  importing = false;

  // Download
  downloadForm: ArchiveDownloadRequest = {
    url: '',
    resumeEnabled: true,
    verifyChecksum: true,
    autoImport: true,
    forceOverwrite: false
  };
  downloading = false;

  constructor(
    public archiveService: ArchiveService,
    private stagingService: StagingService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadInstalledArchives();
    this.loadAvailableModels();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ==================== Installed Archives ====================

  loadInstalledArchives(): void {
    this.loadingArchives = true;
    this.archiveService.listArchives()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (archives) => {
          this.installedArchives = archives;
          this.loadingArchives = false;
        },
        error: (error) => {
          this.showError('Failed to load archives: ' + error.message);
          this.loadingArchives = false;
        }
      });
  }

  uninstallArchive(archive: ArchiveInstallInfo): void {
    if (confirm(`Are you sure you want to uninstall archive "${archive.archiveId}"?`)) {
      this.archiveService.uninstallArchive(archive.archiveId)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.showSuccess('Archive uninstalled successfully');
            this.loadInstalledArchives();
          },
          error: (error) => {
            this.showError('Failed to uninstall archive: ' + error.message);
          }
        });
    }
  }

  // ==================== Remote Catalog ====================

  loadRemoteCatalog(refresh: boolean = false): void {
    this.loadingCatalog = true;
    this.archiveService.getRemoteCatalog(refresh)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (catalog) => {
          this.remoteCatalog = catalog;
          this.loadingCatalog = false;
        },
        error: (error) => {
          this.showError('Failed to load remote catalog: ' + error.message);
          this.loadingCatalog = false;
        }
      });
  }

  downloadFromCatalog(entry: RemoteCatalogEntry): void {
    // This would need the download URL from the catalog entry
    this.showInfo('Download functionality - use the Download tab with the archive URL');
  }

  // ==================== Updates ====================

  checkForUpdates(refresh: boolean = false): void {
    this.loadingUpdates = true;
    this.archiveService.checkForUpdates(refresh)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.updateInfo = response;
          this.loadingUpdates = false;
        },
        error: (error) => {
          this.showError('Failed to check for updates: ' + error.message);
          this.loadingUpdates = false;
        }
      });
  }

  applyUpdate(update: UpdateInfo): void {
    this.applyingUpdate = true;
    this.archiveService.applyUpdate(update.archiveId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          if (result.success) {
            this.showSuccess(`Updated ${update.archiveId} to ${result.newVersion}`);
            this.checkForUpdates();
            this.loadInstalledArchives();
          } else {
            this.showError('Update failed: ' + result.error);
          }
          this.applyingUpdate = false;
        },
        error: (error) => {
          this.showError('Failed to apply update: ' + error.message);
          this.applyingUpdate = false;
        }
      });
  }

  applyAllUpdates(): void {
    if (!this.updateInfo?.updatesAvailable) return;

    if (this.updateInfo.majorUpdates > 0) {
      if (!confirm('Some updates may have breaking changes. Continue?')) {
        return;
      }
    }

    this.applyingAllUpdates = true;
    this.archiveService.applyAllUpdates()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          if (result.success) {
            this.showSuccess(`Updated ${result.successCount} archive(s)`);
          } else {
            this.showWarning(`Updated ${result.successCount}, failed ${result.failCount}`);
          }
          this.checkForUpdates();
          this.loadInstalledArchives();
          this.applyingAllUpdates = false;
        },
        error: (error) => {
          this.showError('Failed to apply updates: ' + error.message);
          this.applyingAllUpdates = false;
        }
      });
  }

  // ==================== Export ====================

  loadAvailableModels(): void {
    this.stagingService.getRegistry()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (registry: ModelRegistry) => {
          this.availableModels = Object.keys(registry.models || {});
        },
        error: (error) => {
          console.error('Failed to load models:', error);
        }
      });
  }

  toggleModelSelection(modelId: string): void {
    const index = this.exportForm.modelIds?.indexOf(modelId) ?? -1;
    if (index >= 0) {
      this.exportForm.modelIds?.splice(index, 1);
    } else {
      if (!this.exportForm.modelIds) {
        this.exportForm.modelIds = [];
      }
      this.exportForm.modelIds.push(modelId);
    }
  }

  isModelSelected(modelId: string): boolean {
    return this.exportForm.modelIds?.includes(modelId) ?? false;
  }

  selectAllModels(): void {
    this.exportForm.modelIds = [...this.availableModels];
  }

  clearModelSelection(): void {
    this.exportForm.modelIds = [];
  }

  exportArchive(): void {
    if (!this.exportForm.exportAll && (!this.exportForm.modelIds || this.exportForm.modelIds.length === 0)) {
      this.showError('Please select at least one model to export');
      return;
    }

    this.exporting = true;
    this.archiveService.exportArchive(this.exportForm)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          if (result.success) {
            this.showSuccess(`Archive exported successfully to ${result.archivePath}`);
          } else {
            this.showError('Export failed: ' + result.error);
          }
          this.exporting = false;
        },
        error: (error) => {
          this.showError('Export failed: ' + error.message);
          this.exporting = false;
        }
      });
  }

  // ==================== Import ====================

  importArchive(): void {
    if (!this.importForm.archivePath) {
      this.showError('Please specify an archive path');
      return;
    }

    this.importing = true;
    this.archiveService.importArchive(this.importForm)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          if (result.success) {
            this.showSuccess(`Imported ${result.importedCount} model(s) from ${result.archiveId}`);
            this.loadInstalledArchives();
            this.loadAvailableModels();
          } else {
            this.showError('Import failed: ' + result.error);
          }
          this.importing = false;
        },
        error: (error) => {
          this.showError('Import failed: ' + error.message);
          this.importing = false;
        }
      });
  }

  // ==================== Download ====================

  downloadArchive(): void {
    if (!this.downloadForm.url) {
      this.showError('Please specify a URL');
      return;
    }

    this.downloading = true;
    this.archiveService.downloadArchive(this.downloadForm)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          if (result.success) {
            let message = `Downloaded archive to ${result.archivePath}`;
            if (result.import) {
              message += ` - Imported ${result.import.importedCount} model(s)`;
            }
            this.showSuccess(message);
            this.loadInstalledArchives();
            this.loadAvailableModels();
          } else {
            this.showError('Download failed: ' + result.error);
          }
          this.downloading = false;
        },
        error: (error) => {
          this.showError('Download failed: ' + error.message);
          this.downloading = false;
        }
      });
  }

  // ==================== Tab Change ====================

  onTabChange(index: number): void {
    this.selectedTabIndex = index;
    switch (index) {
      case 0: // Installed
        this.loadInstalledArchives();
        break;
      case 1: // Remote Catalog
        if (!this.remoteCatalog) {
          this.loadRemoteCatalog();
        }
        break;
      case 2: // Updates
        this.checkForUpdates();
        break;
    }
  }

  // ==================== Utilities ====================

  formatSize(bytes: number): string {
    return this.archiveService.formatSize(bytes);
  }

  getVersionDiffClass(diff: string): string {
    switch (diff) {
      case 'MAJOR_UPGRADE': return 'version-major';
      case 'MINOR_UPGRADE': return 'version-minor';
      case 'PATCH_UPGRADE': return 'version-patch';
      default: return '';
    }
  }

  private showSuccess(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 5000,
      panelClass: ['snackbar-success']
    });
  }

  private showError(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 8000,
      panelClass: ['snackbar-error']
    });
  }

  private showWarning(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 6000,
      panelClass: ['snackbar-warning']
    });
  }

  private showInfo(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000
    });
  }
}
