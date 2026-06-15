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
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatListModule } from '@angular/material/list';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ArchiveService } from '../../services/archive.service';
import { ArchiveInfo, ArchiveStatus, ArchiveModelInfo } from '../../models/api-models';

@Component({
  selector: 'app-archive-manager',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatListModule,
    MatTabsModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    MatSnackBarModule
  ],
  templateUrl: './archive-manager.component.html',
  styleUrls: ['./archive-manager.component.css']
})
export class ArchiveManagerComponent implements OnInit, OnDestroy {

  private destroy$ = new Subject<void>();

  // Archive data
  archives: ArchiveInfo[] = [];
  status: ArchiveStatus | null = null;
  models: ArchiveModelInfo[] = [];
  encoders: ArchiveModelInfo[] = [];
  crossEncoders: ArchiveModelInfo[] = [];

  // UI state
  loading = false;
  loadingArchive = false;
  extractingModel = false;
  selectedTab = 0;

  // Forms
  loadArchiveForm: FormGroup;
  extractForm: FormGroup;

  constructor(
    private archiveService: ArchiveService,
    private snackBar: MatSnackBar,
    private fb: FormBuilder
  ) {
    this.loadArchiveForm = this.fb.group({
      archivePath: ['', Validators.required]
    });

    this.extractForm = this.fb.group({
      modelId: ['', Validators.required],
      destinationPath: ['']
    });
  }

  ngOnInit(): void {
    this.loadData();
    this.subscribeToServices();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private loadData(): void {
    this.loading = true;

    // Load archives
    this.archiveService.listArchives()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.loading = false;
        },
        error: (err) => {
          this.loading = false;
          this.showError('Failed to load archives: ' + err.message);
        }
      });

    // Load status
    this.archiveService.getStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe();

    // Load models if archive is loaded
    if (this.archiveService.isArchiveLoaded()) {
      this.archiveService.loadModels()
        .pipe(takeUntil(this.destroy$))
        .subscribe();
    }
  }

  private subscribeToServices(): void {
    this.archiveService.archives$
      .pipe(takeUntil(this.destroy$))
      .subscribe(archives => this.archives = archives);

    this.archiveService.status$
      .pipe(takeUntil(this.destroy$))
      .subscribe(status => this.status = status);

    this.archiveService.models$
      .pipe(takeUntil(this.destroy$))
      .subscribe(models => {
        this.models = models;
        this.encoders = models.filter(m => m.type === 'encoder');
        this.crossEncoders = models.filter(m => m.type === 'cross_encoder');
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ARCHIVE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  loadArchive(archivePath: string): void {
    this.loadingArchive = true;
    this.archiveService.loadArchive(archivePath)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.loadingArchive = false;
          if (status.loaded) {
            this.showSuccess(`Archive loaded: ${status.archiveId} (v${status.contentVersion})`);
            this.loadArchiveForm.reset();
          }
        },
        error: (err) => {
          this.loadingArchive = false;
          this.showError('Failed to load archive: ' + err.message);
        }
      });
  }

  loadArchiveFromList(archive: ArchiveInfo): void {
    this.loadArchive(archive.path);
  }

  unloadArchive(): void {
    this.loadingArchive = true;
    this.archiveService.unloadArchive()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.loadingArchive = false;
          this.showSuccess('Archive unloaded');
        },
        error: (err) => {
          this.loadingArchive = false;
          this.showError('Failed to unload archive: ' + err.message);
        }
      });
  }

  submitLoadArchive(): void {
    if (this.loadArchiveForm.valid) {
      const archivePath = this.loadArchiveForm.get('archivePath')?.value;
      this.loadArchive(archivePath);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // MODEL OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  extractModel(modelId: string, destinationPath?: string): void {
    this.extractingModel = true;
    this.archiveService.extractModel(modelId, destinationPath)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.extractingModel = false;
          if (result.success) {
            this.showSuccess(`Extracted ${result.filesExtracted} files to ${result.destinationPath}`);
            this.extractForm.reset();
          } else {
            this.showError('Extraction failed: ' + result.error);
          }
        },
        error: (err) => {
          this.extractingModel = false;
          this.showError('Failed to extract model: ' + err.message);
        }
      });
  }

  submitExtract(): void {
    if (this.extractForm.valid) {
      const modelId = this.extractForm.get('modelId')?.value;
      const destinationPath = this.extractForm.get('destinationPath')?.value || undefined;
      this.extractModel(modelId, destinationPath);
    }
  }

  extractModelFromList(model: ArchiveModelInfo): void {
    this.extractModel(model.modelId);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // HELPERS
  // ═══════════════════════════════════════════════════════════════════════════════

  formatSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }

  getModelTypeIcon(type: string): string {
    switch (type) {
      case 'encoder': return 'memory';
      case 'cross_encoder': return 'swap_horiz';
      default: return 'extension';
    }
  }

  private showSuccess(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 3000,
      panelClass: ['success-snackbar']
    });
  }

  private showError(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 5000,
      panelClass: ['error-snackbar']
    });
  }

  refreshArchives(): void {
    this.loadData();
  }
}
