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
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { StagingService } from '../../services/staging.service';
import { StagingModelInfo, ModelType } from '../../models/api-models';

interface SourceOption {
  value: string;
  label: string;
  icon: string;
  placeholder: string;
  hint: string;
}

@Component({
  selector: 'app-download-model',
  standalone: false,
  templateUrl: './download-model.component.html',
  styleUrls: ['./download-model.component.css']
})
export class DownloadModelComponent implements OnInit, OnDestroy {

  private destroy$ = new Subject<void>();

  downloadForm: FormGroup;
  isDownloading = false;
  currentStaging: StagingModelInfo | null = null;

  sources: SourceOption[] = [
    {
      value: 'huggingface',
      label: 'HuggingFace',
      icon: 'hub',
      placeholder: 'BAAI/bge-base-en-v1.5',
      hint: 'Enter HuggingFace repository (e.g., BAAI/bge-base-en-v1.5)'
    },
    {
      value: 'github',
      label: 'GitHub',
      icon: 'code',
      placeholder: 'owner/repo/releases/download/v1.0/model.onnx',
      hint: 'Enter GitHub release path'
    },
    {
      value: 'http',
      label: 'HTTP/HTTPS',
      icon: 'cloud_download',
      placeholder: 'https://example.com/model.onnx',
      hint: 'Enter full URL to model file'
    },
    {
      value: 's3',
      label: 'Amazon S3',
      icon: 'cloud',
      placeholder: 's3://bucket-name/path/to/model.onnx',
      hint: 'Enter S3 URI (requires configured credentials)'
    }
  ];

  modelTypes: { value: ModelType; label: string }[] = [
    { value: 'dense_encoder', label: 'Dense Encoder (Semantic Retrieval)' },
    { value: 'sparse_encoder', label: 'Sparse Encoder (SPLADE, etc.)' },
    { value: 'cross_encoder', label: 'Cross-Encoder (Reranking)' }
  ];

  formats: { value: string; label: string }[] = [
    { value: 'onnx', label: 'ONNX (.onnx)' },
    { value: 'tensorflow', label: 'TensorFlow (.pb)' },
    { value: 'samediff', label: 'SameDiff (.fb) - No conversion needed' }
  ];

  // Recent downloads for quick access
  recentDownloads: StagingModelInfo[] = [];

  constructor(
    private fb: FormBuilder,
    private stagingService: StagingService,
    private snackBar: MatSnackBar
  ) {
    this.downloadForm = this.fb.group({
      source: ['huggingface', Validators.required],
      repository: ['', Validators.required],
      modelId: ['', Validators.required],
      modelType: ['dense_encoder', Validators.required],
      format: ['onnx', Validators.required],
      autoPromote: [false],
      hfToken: [''],
      revision: ['main']
    });
  }

  ngOnInit(): void {
    this.loadRecentDownloads();

    // Update placeholder when source changes
    this.downloadForm.get('source')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(source => {
        this.updateRepositoryPlaceholder(source);
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  getSelectedSource(): SourceOption | undefined {
    const sourceValue = this.downloadForm.get('source')?.value;
    return this.sources.find(s => s.value === sourceValue);
  }

  updateRepositoryPlaceholder(source: string): void {
    // Placeholder is handled in template via getSelectedSource()
  }

  generateModelId(): void {
    const repo = this.downloadForm.get('repository')?.value;
    if (repo) {
      // Extract model name from repository path
      let modelId = repo;

      // Handle HuggingFace format: owner/model-name
      if (repo.includes('/')) {
        const parts = repo.split('/');
        modelId = parts[parts.length - 1];
      }

      // Handle URLs
      if (repo.includes('://')) {
        const url = new URL(repo);
        const path = url.pathname;
        const filename = path.split('/').pop() || '';
        modelId = filename.replace(/\.(onnx|pb|tf|fb|zip)$/i, '');
      }

      // Clean up the model ID
      modelId = modelId.replace(/[^a-zA-Z0-9_-]/g, '-').toLowerCase();

      this.downloadForm.patchValue({ modelId });
    }
  }

  startDownload(): void {
    if (this.downloadForm.invalid) {
      this.markFormTouched();
      return;
    }

    const formValue = this.downloadForm.value;
    this.isDownloading = true;

    const request = {
      modelId: formValue.modelId,
      source: formValue.source,
      repository: formValue.repository,
      format: formValue.format,
      modelType: formValue.modelType,
      autoPromote: formValue.autoPromote,
      revision: formValue.revision,
      token: formValue.hfToken || undefined
    };

    this.stagingService.stageModel(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.currentStaging = response.data || null;
          this.showSuccess(`Started staging ${formValue.modelId}`);
          this.pollStagingStatus(formValue.modelId);
        },
        error: (error) => {
          this.isDownloading = false;
          this.showError(`Failed to start download: ${error.message}`);
        }
      });
  }

  pollStagingStatus(modelId: string): void {
    this.stagingService.pollModelStatus(modelId, 2000)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.currentStaging = status;

          if (status.status === 'completed' || status.status === 'failed') {
            this.isDownloading = false;

            if (status.status === 'completed') {
              this.showSuccess(`Model ${modelId} staged successfully!`);
              this.loadRecentDownloads();
              this.resetForm();
            } else {
              this.showError(`Staging failed: ${status.error || 'Unknown error'}`);
            }
          }
        },
        error: (error) => {
          // Model might not be found if staging completed very quickly
          if (this.currentStaging?.status !== 'completed') {
            console.error('Polling error:', error);
          }
        }
      });
  }

  cancelDownload(): void {
    if (this.currentStaging) {
      this.stagingService.cancelStaging(this.currentStaging.model_id)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.isDownloading = false;
            this.currentStaging = null;
            this.showSuccess('Download cancelled');
          },
          error: (error) => {
            this.showError(`Failed to cancel: ${error.message}`);
          }
        });
    }
  }

  loadRecentDownloads(): void {
    this.stagingService.getModelsInStaging()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (models) => {
          this.recentDownloads = models.slice(0, 5);
        },
        error: () => {
          // Ignore errors for recent downloads
        }
      });
  }

  resetForm(): void {
    this.downloadForm.patchValue({
      repository: '',
      modelId: '',
      autoPromote: false,
      hfToken: '',
      revision: 'main'
    });
    this.currentStaging = null;
  }

  getProgressPercent(): number {
    return this.currentStaging?.progress || 0;
  }

  getStatusMessage(): string {
    if (!this.currentStaging) return '';

    switch (this.currentStaging.status) {
      case 'pending': return 'Preparing...';
      case 'downloading': return `Downloading... ${this.currentStaging.progress}%`;
      case 'converting': return 'Converting to SameDiff...';
      case 'validating': return 'Validating model...';
      case 'ready': return 'Ready for promotion';
      case 'promoting': return 'Promoting to registry...';
      case 'completed': return 'Completed!';
      case 'failed': return `Failed: ${this.currentStaging.error}`;
      default: return this.currentStaging.message || '';
    }
  }

  getStatusIcon(): string {
    if (!this.currentStaging) return 'hourglass_empty';

    switch (this.currentStaging.status) {
      case 'pending': return 'hourglass_empty';
      case 'downloading': return 'cloud_download';
      case 'converting': return 'transform';
      case 'validating': return 'fact_check';
      case 'ready': return 'verified';
      case 'promoting': return 'publish';
      case 'completed': return 'check_circle';
      case 'failed': return 'error';
      default: return 'hourglass_empty';
    }
  }

  private markFormTouched(): void {
    Object.keys(this.downloadForm.controls).forEach(key => {
      this.downloadForm.get(key)?.markAsTouched();
    });
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
}
