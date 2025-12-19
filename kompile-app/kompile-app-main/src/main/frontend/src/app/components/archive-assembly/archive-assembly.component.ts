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
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

// Material imports
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatBadgeModule } from '@angular/material/badge';
import { MatStepperModule } from '@angular/material/stepper';

import { ModelCatalogService } from '../../services/model-catalog.service';
import {
  BuiltInModelCatalog,
  BuiltInModelInfo,
  AssembleArchiveRequest,
  AssembleArchiveResponse,
  getModelTypeIcon,
  getModelTypeDisplayName,
  getModelTypeDescription
} from '../../models/api-models';

@Component({
  selector: 'app-archive-assembly',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatCheckboxModule,
    MatExpansionModule,
    MatChipsModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatBadgeModule,
    MatStepperModule,
    MatSnackBarModule
  ],
  templateUrl: './archive-assembly.component.html',
  styleUrls: ['./archive-assembly.component.css']
})
export class ArchiveAssemblyComponent implements OnInit, OnDestroy {

  private destroy$ = new Subject<void>();

  // Catalog state
  catalog: BuiltInModelCatalog | null = null;
  loading = false;
  error: string | null = null;

  // Selection state
  selectedDenseEncoders = new Set<string>();
  selectedSparseEncoders = new Set<string>();
  selectedCrossEncoders = new Set<string>();

  // Archive config form
  archiveForm: FormGroup;

  // Assembly state
  assembling = false;
  assemblyResult: AssembleArchiveResponse | null = null;

  // Stepper step
  currentStep = 0;

  // Helper functions exposed to template
  getModelTypeIcon = getModelTypeIcon;
  getModelTypeDisplayName = getModelTypeDisplayName;
  getModelTypeDescription = getModelTypeDescription;

  constructor(
    private catalogService: ModelCatalogService,
    private snackBar: MatSnackBar,
    private fb: FormBuilder
  ) {
    this.archiveForm = this.fb.group({
      archiveName: ['My Custom Archive', [Validators.required, Validators.minLength(3)]],
      archiveId: [''],
      description: ['Custom archive assembled from the model catalog'],
      version: ['1.0.0', [Validators.pattern(/^\d+\.\d+\.\d+$/)]],
    });
  }

  ngOnInit(): void {
    this.loadCatalog();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // DATA LOADING
  // ═══════════════════════════════════════════════════════════════════════════════

  loadCatalog(): void {
    this.loading = true;
    this.error = null;

    this.catalogService.loadCatalog()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (catalog) => {
          this.catalog = catalog;
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load model catalog. Please try again.';
          this.loading = false;
          console.error('Failed to load catalog:', err);
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // MODEL SELECTION
  // ═══════════════════════════════════════════════════════════════════════════════

  toggleDenseEncoder(modelId: string): void {
    if (this.selectedDenseEncoders.has(modelId)) {
      this.selectedDenseEncoders.delete(modelId);
    } else {
      this.selectedDenseEncoders.add(modelId);
    }
  }

  toggleSparseEncoder(modelId: string): void {
    if (this.selectedSparseEncoders.has(modelId)) {
      this.selectedSparseEncoders.delete(modelId);
    } else {
      this.selectedSparseEncoders.add(modelId);
    }
  }

  toggleCrossEncoder(modelId: string): void {
    if (this.selectedCrossEncoders.has(modelId)) {
      this.selectedCrossEncoders.delete(modelId);
    } else {
      this.selectedCrossEncoders.add(modelId);
    }
  }

  isDenseEncoderSelected(modelId: string): boolean {
    return this.selectedDenseEncoders.has(modelId);
  }

  isSparseEncoderSelected(modelId: string): boolean {
    return this.selectedSparseEncoders.has(modelId);
  }

  isCrossEncoderSelected(modelId: string): boolean {
    return this.selectedCrossEncoders.has(modelId);
  }

  selectAllDenseEncoders(): void {
    this.catalog?.denseEncoders.forEach(m => this.selectedDenseEncoders.add(m.modelId));
  }

  selectAllSparseEncoders(): void {
    this.catalog?.sparseEncoders.forEach(m => this.selectedSparseEncoders.add(m.modelId));
  }

  selectAllCrossEncoders(): void {
    this.catalog?.crossEncoders.forEach(m => this.selectedCrossEncoders.add(m.modelId));
  }

  clearAllDenseEncoders(): void {
    this.selectedDenseEncoders.clear();
  }

  clearAllSparseEncoders(): void {
    this.selectedSparseEncoders.clear();
  }

  clearAllCrossEncoders(): void {
    this.selectedCrossEncoders.clear();
  }

  clearAllSelections(): void {
    this.selectedDenseEncoders.clear();
    this.selectedSparseEncoders.clear();
    this.selectedCrossEncoders.clear();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // COUNTS AND SUMMARY
  // ═══════════════════════════════════════════════════════════════════════════════

  get totalSelectedCount(): number {
    return this.selectedDenseEncoders.size +
           this.selectedSparseEncoders.size +
           this.selectedCrossEncoders.size;
  }

  get hasSelection(): boolean {
    return this.totalSelectedCount > 0;
  }

  get hasRetrievalModel(): boolean {
    return this.selectedDenseEncoders.size > 0 || this.selectedSparseEncoders.size > 0;
  }

  get hasRerankingModel(): boolean {
    return this.selectedCrossEncoders.size > 0;
  }

  getSelectedDenseEncoderModels(): BuiltInModelInfo[] {
    if (!this.catalog) return [];
    return this.catalog.denseEncoders.filter(m => this.selectedDenseEncoders.has(m.modelId));
  }

  getSelectedSparseEncoderModels(): BuiltInModelInfo[] {
    if (!this.catalog) return [];
    return this.catalog.sparseEncoders.filter(m => this.selectedSparseEncoders.has(m.modelId));
  }

  getSelectedCrossEncoderModels(): BuiltInModelInfo[] {
    if (!this.catalog) return [];
    return this.catalog.crossEncoders.filter(m => this.selectedCrossEncoders.has(m.modelId));
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ARCHIVE ASSEMBLY
  // ═══════════════════════════════════════════════════════════════════════════════

  assembleArchive(): void {
    if (!this.hasSelection) {
      this.snackBar.open('Please select at least one model', 'Close', { duration: 3000 });
      return;
    }

    if (this.archiveForm.invalid) {
      this.snackBar.open('Please fill in the required archive information', 'Close', { duration: 3000 });
      return;
    }

    this.assembling = true;
    this.assemblyResult = null;

    const formValue = this.archiveForm.value;
    const request: AssembleArchiveRequest = {
      archiveId: formValue.archiveId || undefined,
      archiveName: formValue.archiveName,
      description: formValue.description,
      version: formValue.version,
      denseEncoderIds: Array.from(this.selectedDenseEncoders),
      sparseEncoderIds: Array.from(this.selectedSparseEncoders),
      crossEncoderIds: Array.from(this.selectedCrossEncoders)
    };

    this.catalogService.assembleArchive(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.assemblyResult = result;
          this.assembling = false;
          if (result.success) {
            this.snackBar.open(`Archive created with ${result.modelCount} models`, 'Close', {
              duration: 5000,
              panelClass: ['success-snackbar']
            });
            this.currentStep = 3; // Move to summary step
          } else {
            this.snackBar.open(`Assembly failed: ${result.error}`, 'Close', {
              duration: 5000,
              panelClass: ['error-snackbar']
            });
          }
        },
        error: (err) => {
          this.assembling = false;
          this.snackBar.open('Failed to assemble archive', 'Close', {
            duration: 5000,
            panelClass: ['error-snackbar']
          });
          console.error('Assembly error:', err);
        }
      });
  }

  resetAssembly(): void {
    this.clearAllSelections();
    this.assemblyResult = null;
    this.archiveForm.reset({
      archiveName: 'My Custom Archive',
      archiveId: '',
      description: 'Custom archive assembled from the model catalog',
      version: '1.0.0'
    });
    this.currentStep = 0;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // HELPERS
  // ═══════════════════════════════════════════════════════════════════════════════

  formatDimension(dim: number | null): string {
    if (dim === null) return 'N/A';
    if (dim >= 1000) return `${(dim / 1000).toFixed(1)}k`;
    return dim.toString();
  }

  formatSequenceLength(len: number | null): string {
    if (len === null) return 'N/A';
    if (len >= 1000) return `${(len / 1000).toFixed(0)}k`;
    return len.toString();
  }

  getModelSizeEstimate(model: BuiltInModelInfo): string {
    // Rough estimate based on embedding dim and layers
    const embeddingDim = model.embeddingDim || 768;
    const numLayers = model.numLayers || 12;
    const estimatedMB = Math.round((embeddingDim * numLayers * 0.1));
    if (estimatedMB < 100) return `~${estimatedMB} MB`;
    return `~${(estimatedMB / 1000).toFixed(1)} GB`;
  }
}
