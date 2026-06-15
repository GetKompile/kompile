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

import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { VlmService } from '../../services/vlm.service';
import {
  VlmModelSet,
  VlmPipelineStage,
  VlmExtractionType,
  VlmPreset,
  VlmDownloadStatus,
  VlmModelSetsStatus,
  VlmServiceStatus
} from '../../models/api-models';

@Component({
  selector: 'app-vlm-models',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    MatProgressBarModule,
    MatExpansionModule,
    MatChipsModule,
    MatTooltipModule,
    MatSnackBarModule
  ],
  templateUrl: './vlm-models.component.html',
  styleUrls: ['./vlm-models.component.css']
})
export class VlmModelsComponent implements OnInit, OnDestroy {

  // State
  isLoading = false;
  modelSets: VlmModelSet[] = [];
  pipelineStages: VlmPipelineStage[] = [];
  extractionTypes: VlmExtractionType[] = [];
  presets: VlmPreset[] = [];
  serviceStatus: VlmServiceStatus | null = null;
  modelSetsStatus: VlmModelSetsStatus | null = null;

  // Download state
  downloadingSetId: string | null = null;
  downloadProgress: VlmDownloadStatus | null = null;

  // UI state
  expandedSetId: string | null = null;
  selectedPresetId: string | null = null;
  showPipelineStages = false;

  private destroy$ = new Subject<void>();

  constructor(
    private vlmService: VlmService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadAllData();

    // Subscribe to download events
    this.vlmService.downloadEvents$
      .pipe(takeUntil(this.destroy$))
      .subscribe(status => {
        this.downloadProgress = status;
        if (status.complete) {
          this.downloadingSetId = null;
          this.loadModelSetsStatus();
        }
        this.cdr.detectChanges();
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadAllData(): void {
    this.isLoading = true;

    Promise.all([
      this.loadModelSets(),
      this.loadModelSetsStatus(),
      this.loadPipelineStages(),
      this.loadExtractionTypes(),
      this.loadPresets(),
      this.loadServiceStatus()
    ]).finally(() => {
      this.isLoading = false;
      this.cdr.detectChanges();
    });
  }

  private loadModelSets(): Promise<void> {
    return new Promise(resolve => {
      this.vlmService.getModelSets()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: sets => {
            this.modelSets = sets;
            resolve();
          },
          error: err => {
            console.error('Failed to load model sets:', err);
            resolve();
          }
        });
    });
  }

  private loadModelSetsStatus(): Promise<void> {
    return new Promise(resolve => {
      this.vlmService.getModelSetsStatus()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: status => {
            this.modelSetsStatus = status;
            // Update cached status in model sets
            this.modelSets = this.modelSets.map(set => ({
              ...set,
              cached: status.modelSets[set.setId] || false
            }));
            resolve();
          },
          error: err => {
            console.error('Failed to load model sets status:', err);
            resolve();
          }
        });
    });
  }

  private loadPipelineStages(): Promise<void> {
    return new Promise(resolve => {
      this.vlmService.getPipelineStages()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: stages => {
            this.pipelineStages = stages;
            resolve();
          },
          error: err => {
            console.error('Failed to load pipeline stages:', err);
            resolve();
          }
        });
    });
  }

  private loadExtractionTypes(): Promise<void> {
    return new Promise(resolve => {
      this.vlmService.getExtractionTypes()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: types => {
            this.extractionTypes = types;
            resolve();
          },
          error: err => {
            console.error('Failed to load extraction types:', err);
            resolve();
          }
        });
    });
  }

  private loadPresets(): Promise<void> {
    return new Promise(resolve => {
      this.vlmService.getPresets()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: presets => {
            this.presets = presets;
            resolve();
          },
          error: err => {
            console.error('Failed to load presets:', err);
            resolve();
          }
        });
    });
  }

  private loadServiceStatus(): Promise<void> {
    return new Promise(resolve => {
      this.vlmService.getServiceStatus()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: status => {
            this.serviceStatus = status;
            resolve();
          },
          error: err => {
            console.error('Failed to load service status:', err);
            resolve();
          }
        });
    });
  }

  // ==================== Model Set Operations ====================

  downloadModelSet(setId: string): void {
    if (this.downloadingSetId) {
      this.showSnackbar('A download is already in progress', true);
      return;
    }

    this.downloadingSetId = setId;
    this.downloadProgress = null;

    this.vlmService.downloadModelSetWithProgress(setId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: status => {
          this.downloadProgress = status;
          if (status.complete) {
            if (status.success) {
              this.showSnackbar(`Model set "${setId}" downloaded successfully`);
            } else {
              this.showSnackbar(`Download failed: ${status.message}`, true);
            }
            this.downloadingSetId = null;
            this.loadModelSetsStatus();
          }
          this.cdr.detectChanges();
        },
        error: err => {
          this.downloadingSetId = null;
          this.showSnackbar(`Download failed: ${err.message}`, true);
          this.cdr.detectChanges();
        }
      });
  }

  deleteModelSet(setId: string): void {
    if (!confirm(`Are you sure you want to delete the cached model "${setId}"?`)) {
      return;
    }

    this.vlmService.deleteModelSet(setId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          if (response.success) {
            this.showSnackbar(`Model set "${setId}" deleted`);
            this.loadModelSetsStatus();
          } else {
            this.showSnackbar(`Delete failed: ${response.error}`, true);
          }
        },
        error: err => {
          this.showSnackbar(`Delete failed: ${err.message}`, true);
        }
      });
  }

  toggleSetExpansion(setId: string): void {
    this.expandedSetId = this.expandedSetId === setId ? null : setId;
  }

  isSetExpanded(setId: string): boolean {
    return this.expandedSetId === setId;
  }

  // ==================== Preset Operations ====================

  ensurePresetModels(presetId: string): void {
    this.selectedPresetId = presetId;

    this.vlmService.ensurePresetModels(presetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          this.selectedPresetId = null;
          if (response.success) {
            this.showSnackbar(`All models for "${presetId}" are ready`);
          } else {
            this.showSnackbar(`Some models failed: ${(response.failures || []).join(', ')}`, true);
          }
          this.loadModelSetsStatus();
        },
        error: err => {
          this.selectedPresetId = null;
          this.showSnackbar(`Failed to ensure models: ${err.message}`, true);
        }
      });
  }

  // ==================== UI Helpers ====================

  refreshData(): void {
    this.loadAllData();
    this.showSnackbar('Data refreshed');
  }

  togglePipelineStages(): void {
    this.showPipelineStages = !this.showPipelineStages;
  }

  getModelSetIcon(setId: string): string {
    if (setId.includes('docling') || setId.includes('donut')) {
      return 'description';
    } else if (setId.includes('clip') || setId.includes('siglip')) {
      return 'image';
    } else if (setId.includes('table')) {
      return 'table_chart';
    }
    return 'memory';
  }

  getPipelineStageIcon(stage: VlmPipelineStage): string {
    if (stage.id.includes('IMAGE') || stage.id.includes('VISION')) {
      return 'image';
    } else if (stage.id.includes('TOKEN')) {
      return 'text_fields';
    } else if (stage.id.includes('EMBED')) {
      return 'data_array';
    } else if (stage.id.includes('DECODE') || stage.id.includes('AUTOREGRESSIVE')) {
      return 'autorenew';
    } else if (stage.id.includes('SAMPLE')) {
      return 'casino';
    } else if (stage.id.includes('FUSION')) {
      return 'merge_type';
    }
    return 'settings';
  }

  getExtractionTypeIcon(typeId: string): string {
    switch (typeId) {
      case 'document-understanding': return 'description';
      case 'table-extraction': return 'table_chart';
      case 'figure-understanding': return 'insert_chart';
      case 'form-extraction': return 'assignment';
      case 'image-embedding': return 'image_search';
      case 'ocr-with-layout': return 'text_format';
      default: return 'memory';
    }
  }

  getPresetIcon(presetId: string): string {
    switch (presetId) {
      case 'scanned-documents': return 'scanner';
      case 'text-pdfs': return 'article';
      case 'scientific-papers': return 'science';
      case 'forms': return 'assignment';
      case 'comprehensive': return 'all_inclusive';
      default: return 'settings';
    }
  }

  getCachedModelCount(): number {
    if (!this.modelSetsStatus?.modelSets) return 0;
    return Object.values(this.modelSetsStatus.modelSets).filter(cached => cached).length;
  }

  getTotalModelCount(): number {
    return this.modelSets.length;
  }

  isModelSetCached(setId: string): boolean {
    return this.modelSetsStatus?.modelSets?.[setId] || false;
  }

  isDownloading(setId: string): boolean {
    return this.downloadingSetId === setId;
  }

  getDownloadProgress(): number {
    if (!this.downloadProgress) return 0;
    return (this.downloadProgress.componentProgress || 0) * 100;
  }

  formatComponentCount(count: number): string {
    return count === 1 ? '1 component' : `${count} components`;
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }
}
