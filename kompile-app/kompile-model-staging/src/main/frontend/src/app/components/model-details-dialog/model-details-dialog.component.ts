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

import { Component, Inject, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ModelEntry, ModelType, ModelStatus, getModelTypeDisplayName, getStatusIcon } from '../../models/api-models';
import { StagingService } from '../../services/staging.service';

export interface ModelDetailsDialogData {
  model: ModelEntry;
  editable?: boolean;
}

export interface ModelDetailsDialogResult {
  action: 'updated' | 'deleted' | 'closed';
  model?: ModelEntry;
}

@Component({
  selector: 'app-model-details-dialog',
  standalone: false,
  templateUrl: './model-details-dialog.component.html',
  styleUrls: ['./model-details-dialog.component.css']
})
export class ModelDetailsDialogComponent implements OnInit {

  model: ModelEntry;
  editable: boolean;

  // Forms
  metadataForm: FormGroup;
  tokenizerForm: FormGroup;

  // Edit state
  isEditingMetadata = false;
  isEditingTokenizer = false;
  isSaving = false;
  isDeleting = false;

  // Model options
  modelTypes: { value: ModelType; label: string }[] = [
    { value: 'dense_encoder', label: 'Dense Encoder' },
    { value: 'sparse_encoder', label: 'Sparse Encoder' },
    { value: 'cross_encoder', label: 'Cross-Encoder (Reranker)' },
    { value: 'encoder', label: 'Encoder (Legacy)' }
  ];

  modelStatuses: { value: ModelStatus; label: string }[] = [
    { value: 'active', label: 'Active' },
    { value: 'staged', label: 'Staged' },
    { value: 'deprecated', label: 'Deprecated' }
  ];

  constructor(
    public dialogRef: MatDialogRef<ModelDetailsDialogComponent, ModelDetailsDialogResult>,
    @Inject(MAT_DIALOG_DATA) public data: ModelDetailsDialogData,
    private fb: FormBuilder,
    private stagingService: StagingService,
    private snackBar: MatSnackBar
  ) {
    this.model = data.model;
    this.editable = data.editable ?? false;

    // Initialize metadata form
    this.metadataForm = this.fb.group({
      type: [this.model.type],
      status: [this.model.status],
      description: [this.model.metadata?.description || ''],
      embeddingDim: [this.model.metadata?.embeddingDim],
      hiddenSize: [this.model.metadata?.hiddenSize],
      numLayers: [this.model.metadata?.numLayers],
      maxSequenceLength: [this.model.metadata?.maxSequenceLength],
      framework: [this.model.metadata?.framework || ''],
      trainingData: [this.model.metadata?.trainingData || ''],
      sourceOrigin: [this.model.metadata?.sourceOrigin || ''],
      sourceRepository: [this.model.metadata?.sourceRepository || ''],
      vocabSize: [this.model.metadata?.vocabSize]
    });

    // Initialize tokenizer form
    this.tokenizerForm = this.fb.group({
      do_lower_case: [this.model.tokenizer?.do_lower_case ?? true],
      add_special_tokens: [this.model.tokenizer?.add_special_tokens ?? true],
      strip_accents: [this.model.tokenizer?.strip_accents ?? false],
      max_length: [this.model.tokenizer?.max_length ?? 512, [Validators.min(1), Validators.max(8192)]]
    });
  }

  ngOnInit(): void {}

  getModelTypeDisplayName = getModelTypeDisplayName;
  getStatusIcon = getStatusIcon;

  // ==================== Metadata Editing ====================

  toggleMetadataEdit(): void {
    this.isEditingMetadata = !this.isEditingMetadata;
    if (!this.isEditingMetadata) {
      this.resetMetadataForm();
    }
  }

  resetMetadataForm(): void {
    this.metadataForm.patchValue({
      type: this.model.type,
      status: this.model.status,
      description: this.model.metadata?.description || '',
      embeddingDim: this.model.metadata?.embeddingDim,
      hiddenSize: this.model.metadata?.hiddenSize,
      numLayers: this.model.metadata?.numLayers,
      maxSequenceLength: this.model.metadata?.maxSequenceLength,
      framework: this.model.metadata?.framework || '',
      trainingData: this.model.metadata?.trainingData || '',
      sourceOrigin: this.model.metadata?.sourceOrigin || '',
      sourceRepository: this.model.metadata?.sourceRepository || '',
      vocabSize: this.model.metadata?.vocabSize
    });
  }

  saveMetadata(): void {
    if (this.metadataForm.invalid) return;

    this.isSaving = true;
    const formValue = this.metadataForm.value;

    const updates = {
      type: formValue.type,
      status: formValue.status,
      metadata: {
        description: formValue.description || undefined,
        embeddingDim: formValue.embeddingDim || undefined,
        hiddenSize: formValue.hiddenSize || undefined,
        numLayers: formValue.numLayers || undefined,
        maxSequenceLength: formValue.maxSequenceLength || undefined,
        framework: formValue.framework || undefined,
        trainingData: formValue.trainingData || undefined,
        sourceOrigin: formValue.sourceOrigin || undefined,
        sourceRepository: formValue.sourceRepository || undefined,
        vocabSize: formValue.vocabSize || undefined
      }
    };

    this.stagingService.updateModel(this.model.model_id, updates).subscribe({
      next: (response: any) => {
        this.isSaving = false;
        if (response.success) {
          // Update local model
          this.model.type = formValue.type;
          this.model.status = formValue.status;
          if (!this.model.metadata) {
            this.model.metadata = {} as any;
          }
          Object.assign(this.model.metadata, updates.metadata);

          this.isEditingMetadata = false;
          this.snackBar.open('Model metadata updated successfully', 'Close', {
            duration: 3000,
            panelClass: ['snackbar-success']
          });
        } else {
          this.snackBar.open(response.error || 'Failed to update model', 'Close', {
            duration: 5000,
            panelClass: ['snackbar-error']
          });
        }
      },
      error: (err) => {
        this.isSaving = false;
        this.snackBar.open('Failed to update model: ' + err.message, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  // ==================== Tokenizer Editing ====================

  toggleTokenizerEdit(): void {
    this.isEditingTokenizer = !this.isEditingTokenizer;
    if (!this.isEditingTokenizer) {
      this.resetTokenizerForm();
    }
  }

  resetTokenizerForm(): void {
    this.tokenizerForm.patchValue({
      do_lower_case: this.model.tokenizer?.do_lower_case ?? true,
      add_special_tokens: this.model.tokenizer?.add_special_tokens ?? true,
      strip_accents: this.model.tokenizer?.strip_accents ?? false,
      max_length: this.model.tokenizer?.max_length ?? 512
    });
  }

  saveTokenizerConfig(): void {
    if (this.tokenizerForm.invalid) return;

    this.isSaving = true;
    const formValue = this.tokenizerForm.value;

    const updates = {
      tokenizer: {
        doLowerCase: formValue.do_lower_case,
        addSpecialTokens: formValue.add_special_tokens,
        stripAccents: formValue.strip_accents,
        maxLength: formValue.max_length
      }
    };

    this.stagingService.updateModel(this.model.model_id, updates).subscribe({
      next: (response: any) => {
        this.isSaving = false;
        if (response.success) {
          // Update local model
          this.model.tokenizer = {
            do_lower_case: formValue.do_lower_case,
            add_special_tokens: formValue.add_special_tokens,
            strip_accents: formValue.strip_accents,
            max_length: formValue.max_length
          };

          this.isEditingTokenizer = false;
          this.snackBar.open('Tokenizer configuration updated successfully', 'Close', {
            duration: 3000,
            panelClass: ['snackbar-success']
          });
        } else {
          this.snackBar.open(response.error || 'Failed to update tokenizer', 'Close', {
            duration: 5000,
            panelClass: ['snackbar-error']
          });
        }
      },
      error: (err) => {
        this.isSaving = false;
        this.snackBar.open('Failed to update tokenizer: ' + err.message, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  // ==================== Delete Model ====================

  confirmDelete(): void {
    if (!confirm(`Are you sure you want to delete model "${this.model.model_id}"? This action cannot be undone.`)) {
      return;
    }

    this.isDeleting = true;

    this.stagingService.deleteModel(this.model.model_id).subscribe({
      next: (response: any) => {
        this.isDeleting = false;
        if (response.success) {
          this.snackBar.open('Model deleted successfully', 'Close', {
            duration: 3000,
            panelClass: ['snackbar-success']
          });
          this.dialogRef.close({ action: 'deleted', model: this.model });
        } else {
          this.snackBar.open(response.error || 'Failed to delete model', 'Close', {
            duration: 5000,
            panelClass: ['snackbar-error']
          });
        }
      },
      error: (err) => {
        this.isDeleting = false;
        this.snackBar.open('Failed to delete model: ' + err.message, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  // ==================== Display Helpers ====================

  getMetadataItems(): { label: string; value: any; icon: string }[] {
    const meta = this.model.metadata;
    if (!meta) return [];

    const items: { label: string; value: any; icon: string }[] = [];

    if (meta.embeddingDim) {
      items.push({ label: 'Embedding Dimension', value: meta.embeddingDim, icon: 'straighten' });
    }
    if (meta.hiddenSize) {
      items.push({ label: 'Hidden Size', value: meta.hiddenSize, icon: 'layers' });
    }
    if (meta.numLayers) {
      items.push({ label: 'Number of Layers', value: meta.numLayers, icon: 'view_module' });
    }
    if (meta.maxSequenceLength) {
      items.push({ label: 'Max Sequence Length', value: meta.maxSequenceLength, icon: 'text_fields' });
    }
    if (meta.framework) {
      items.push({ label: 'Framework', value: meta.framework, icon: 'code' });
    }
    if (meta.originalFormat) {
      items.push({ label: 'Original Format', value: meta.originalFormat, icon: 'transform' });
    }
    if (meta.sourceOrigin) {
      items.push({ label: 'Source', value: meta.sourceOrigin, icon: 'cloud_download' });
    }
    if (meta.sourceRepository) {
      items.push({ label: 'Repository', value: meta.sourceRepository, icon: 'folder' });
    }
    if (meta.conversionDate) {
      items.push({ label: 'Converted', value: new Date(meta.conversionDate).toLocaleDateString(), icon: 'event' });
    }
    if (meta.trainingData) {
      items.push({ label: 'Training Data', value: meta.trainingData, icon: 'school' });
    }

    return items;
  }

  getFileInfo(): { label: string; value: string; icon: string }[] {
    return [
      { label: 'Model File', value: this.model.model_file, icon: 'insert_drive_file' },
      { label: 'Vocabulary File', value: this.model.vocab_file, icon: 'description' },
      { label: 'Path', value: this.model.path, icon: 'folder' },
      { label: 'Checksum', value: this.model.checksum || 'Not available', icon: 'fingerprint' }
    ];
  }

  copyToClipboard(value: string): void {
    navigator.clipboard.writeText(value).then(() => {
      this.snackBar.open('Copied to clipboard', 'Close', {
        duration: 2000
      });
    });
  }

  copyModelJson(): void {
    const jsonString = JSON.stringify(this.model, null, 2);
    navigator.clipboard.writeText(jsonString).then(() => {
      this.snackBar.open('JSON copied to clipboard', 'Close', {
        duration: 2000
      });
    });
  }

  close(): void {
    this.dialogRef.close({ action: 'closed' });
  }
}
