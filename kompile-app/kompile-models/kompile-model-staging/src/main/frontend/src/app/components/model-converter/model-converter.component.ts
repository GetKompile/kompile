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

import { Component, OnInit, OnDestroy, ViewChild, ElementRef } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { StagingService } from '../../services/staging.service';
import { StagingModelInfo } from '../../models/api-models';
import { Subscription, interval } from 'rxjs';
import { takeWhile } from 'rxjs/operators';

interface FormatOption {
  value: string;
  label: string;
  extensions: string;
  icon: string;
  description: string;
}

interface SourceOption {
  value: string;
  label: string;
  icon: string;
  description: string;
}

interface DirectoryEntry {
  name: string;
  path: string;
  isDirectory: boolean;
  size?: number;
  modifiedAt?: string;
}

interface ConversionJob {
  id: string;
  modelId: string;
  inputPath: string;
  format: string;
  status: 'pending' | 'uploading' | 'converting' | 'validating' | 'completed' | 'failed';
  progress: number;
  message: string;
  startedAt: Date;
  completedAt?: Date;
  error?: string;
  result?: {
    outputPath: string;
    checksum: string;
    numOperations: number;
    numVariables: number;
    durationMs: number;
  };
}

@Component({
  selector: 'app-model-converter',
  standalone: false,
  templateUrl: './model-converter.component.html',
  styleUrls: ['./model-converter.component.css']
})
export class ModelConverterComponent implements OnInit, OnDestroy {

  @ViewChild('fileInput') fileInput!: ElementRef<HTMLInputElement>;

  conversionForm: FormGroup;
  isConverting = false;
  isUploading = false;
  uploadProgress = 0;
  conversionJobs: ConversionJob[] = [];
  private pollSubscription?: Subscription;

  // Source selection
  selectedSource: string = 'local';
  sources: SourceOption[] = [
    {
      value: 'local',
      label: 'Local Path',
      icon: 'folder',
      description: 'Specify a file path on the server'
    },
    {
      value: 'upload',
      label: 'Upload File',
      icon: 'cloud_upload',
      description: 'Upload a model file from your computer'
    },
    {
      value: 'staged',
      label: 'Staged Model',
      icon: 'pending_actions',
      description: 'Convert a model currently in staging'
    },
    {
      value: 'models',
      label: 'Models Directory',
      icon: 'inventory_2',
      description: 'Select from the local models directory'
    }
  ];

  // File upload
  selectedFile: File | null = null;
  dragOver = false;

  // Staged models
  stagedModels: StagingModelInfo[] = [];
  selectedStagedModel: StagingModelInfo | null = null;
  loadingStagedModels = false;

  // Directory browser
  currentDirectory: string = '';
  directoryEntries: DirectoryEntry[] = [];
  loadingDirectory = false;
  selectedDirectoryFile: DirectoryEntry | null = null;

  formats: FormatOption[] = [
    {
      value: 'onnx',
      label: 'ONNX',
      extensions: '.onnx',
      icon: 'hub',
      description: 'Open Neural Network Exchange format'
    },
    {
      value: 'tensorflow',
      label: 'TensorFlow',
      extensions: '.pb',
      icon: 'memory',
      description: 'TensorFlow frozen graph format'
    },
    {
      value: 'keras',
      label: 'Keras',
      extensions: '.h5, .keras',
      icon: 'layers',
      description: 'Keras model format'
    }
  ];

  modelTypes = [
    { value: 'dense_encoder', label: 'Dense Encoder', description: 'Dense bi-encoder for semantic retrieval (e.g., BGE, Arctic)' },
    { value: 'sparse_encoder', label: 'Sparse Encoder', description: 'Sparse encoder for learned sparse retrieval (e.g., SPLADE)' },
    { value: 'cross_encoder', label: 'Cross-Encoder (Reranker)', description: 'Neural reranker that scores query-document pairs' }
  ];

  constructor(
    private fb: FormBuilder,
    private stagingService: StagingService,
    private snackBar: MatSnackBar
  ) {
    this.conversionForm = this.fb.group({
      inputPath: [''],
      format: ['onnx', Validators.required],
      autoDetectFormat: [true],
      modelId: ['', [Validators.required, Validators.pattern(/^[a-zA-Z0-9_-]+$/)]],
      modelType: ['dense_encoder', Validators.required],
      outputPath: [''],
      autoStage: [true],
      autoPromote: [false],
      // Metadata
      description: [''],
      embeddingDim: [null],
      maxSequenceLength: [512, [Validators.min(1), Validators.max(8192)]]
    });
  }

  ngOnInit(): void {
    this.loadRecentJobs();
    this.loadStagedModels();
    this.loadModelsDirectory();
  }

  ngOnDestroy(): void {
    this.pollSubscription?.unsubscribe();
  }

  // ==================== Source Selection ====================

  selectSource(source: string): void {
    this.selectedSource = source;
    this.selectedFile = null;
    this.selectedStagedModel = null;
    this.selectedDirectoryFile = null;
    this.conversionForm.patchValue({ inputPath: '' });

    if (source === 'staged') {
      this.loadStagedModels();
    } else if (source === 'models') {
      this.loadModelsDirectory();
    }
  }

  // ==================== File Upload ====================

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.handleFile(input.files[0]);
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.dragOver = true;
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.dragOver = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.dragOver = false;

    if (event.dataTransfer?.files && event.dataTransfer.files.length > 0) {
      this.handleFile(event.dataTransfer.files[0]);
    }
  }

  handleFile(file: File): void {
    const validExtensions = ['.onnx', '.pb', '.h5', '.keras'];
    const ext = '.' + file.name.split('.').pop()?.toLowerCase();

    if (!validExtensions.includes(ext)) {
      this.snackBar.open('Invalid file type. Supported: ONNX, TensorFlow (.pb), Keras (.h5, .keras)', 'Close', { duration: 5000 });
      return;
    }

    this.selectedFile = file;
    this.detectFormatFromFile(file.name);
    this.generateModelIdFromFilename(file.name);
  }

  detectFormatFromFile(filename: string): void {
    const ext = filename.toLowerCase().split('.').pop();
    let format = 'onnx';

    if (ext === 'pb') {
      format = 'tensorflow';
    } else if (ext === 'h5' || ext === 'keras') {
      format = 'keras';
    }

    if (this.conversionForm.get('autoDetectFormat')?.value) {
      this.conversionForm.patchValue({ format });
    }
  }

  generateModelIdFromFilename(filename: string): void {
    const name = filename.replace(/\.[^.]+$/, '');
    const modelId = name.toLowerCase().replace(/[^a-z0-9_-]/g, '-').replace(/-+/g, '-');

    if (!this.conversionForm.get('modelId')?.dirty) {
      this.conversionForm.patchValue({ modelId });
    }
  }

  clearFile(): void {
    this.selectedFile = null;
    if (this.fileInput) {
      this.fileInput.nativeElement.value = '';
    }
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }

  // ==================== Staged Models ====================

  loadStagedModels(): void {
    this.loadingStagedModels = true;
    this.stagingService.getModelsInStaging().subscribe({
      next: (models) => {
        // Filter to only show models that can be converted (not yet completed)
        this.stagedModels = models.filter(m =>
          m.status === 'ready' || m.status === 'pending' || m.status === 'downloading'
        );
        this.loadingStagedModels = false;
      },
      error: (err) => {
        console.error('Failed to load staged models:', err);
        this.loadingStagedModels = false;
      }
    });
  }

  selectStagedModel(model: StagingModelInfo): void {
    this.selectedStagedModel = model;
    this.conversionForm.patchValue({
      modelId: model.model_id,
      inputPath: model.model_id // Will be resolved by backend
    });
  }

  // ==================== Directory Browser ====================

  loadModelsDirectory(path: string = ''): void {
    this.loadingDirectory = true;
    this.stagingService.listDirectory(path).subscribe({
      next: (response) => {
        this.currentDirectory = response.path || path;
        this.directoryEntries = response.entries || [];
        this.loadingDirectory = false;
      },
      error: (err) => {
        console.error('Failed to load directory:', err);
        this.directoryEntries = [];
        this.loadingDirectory = false;
      }
    });
  }

  navigateToDirectory(entry: DirectoryEntry): void {
    if (entry.isDirectory) {
      this.loadModelsDirectory(entry.path);
      this.selectedDirectoryFile = null;
    }
  }

  navigateUp(): void {
    const parts = this.currentDirectory.split('/').filter(p => p);
    parts.pop();
    this.loadModelsDirectory(parts.join('/'));
  }

  selectDirectoryFile(entry: DirectoryEntry): void {
    if (!entry.isDirectory) {
      this.selectedDirectoryFile = entry;
      this.conversionForm.patchValue({ inputPath: entry.path });
      this.detectFormatFromFile(entry.name);
      this.generateModelIdFromFilename(entry.name);
    }
  }

  getFileIcon(entry: DirectoryEntry): string {
    if (entry.isDirectory) return 'folder';
    const ext = entry.name.split('.').pop()?.toLowerCase();
    switch (ext) {
      case 'onnx': return 'hub';
      case 'pb': return 'memory';
      case 'h5':
      case 'keras': return 'layers';
      case 'sdz':
      case 'fb': return 'check_circle';
      default: return 'insert_drive_file';
    }
  }

  isConvertibleFile(entry: DirectoryEntry): boolean {
    if (entry.isDirectory) return false;
    const ext = entry.name.split('.').pop()?.toLowerCase();
    return ['onnx', 'pb', 'h5', 'keras'].includes(ext || '');
  }

  // ==================== Conversion ====================

  getSelectedFormat(): FormatOption | undefined {
    const formatValue = this.conversionForm.get('format')?.value;
    return this.formats.find(f => f.value === formatValue);
  }

  canStartConversion(): boolean {
    if (this.conversionForm.invalid || this.isConverting) return false;

    switch (this.selectedSource) {
      case 'local':
        return !!this.conversionForm.get('inputPath')?.value;
      case 'upload':
        return !!this.selectedFile;
      case 'staged':
        return !!this.selectedStagedModel;
      case 'models':
        return !!this.selectedDirectoryFile;
      default:
        return false;
    }
  }

  startConversion(): void {
    if (!this.canStartConversion()) {
      this.snackBar.open('Please complete all required fields', 'Close', { duration: 3000 });
      return;
    }

    const formValue = this.conversionForm.value;

    // Create job for tracking
    const job: ConversionJob = {
      id: Date.now().toString(),
      modelId: formValue.modelId,
      inputPath: this.getInputPath(),
      format: formValue.format,
      status: 'pending',
      progress: 0,
      message: 'Starting conversion...',
      startedAt: new Date()
    };

    this.conversionJobs.unshift(job);
    this.isConverting = true;

    if (this.selectedSource === 'upload' && this.selectedFile) {
      // Upload file first, then convert
      this.uploadAndConvert(job, this.selectedFile);
    } else {
      // Direct conversion
      this.executeConversion(job);
    }
  }

  getInputPath(): string {
    switch (this.selectedSource) {
      case 'local':
        return this.conversionForm.get('inputPath')?.value || '';
      case 'upload':
        return this.selectedFile?.name || '';
      case 'staged':
        return this.selectedStagedModel?.model_id || '';
      case 'models':
        return this.selectedDirectoryFile?.path || '';
      default:
        return '';
    }
  }

  uploadAndConvert(job: ConversionJob, file: File): void {
    job.status = 'uploading';
    job.message = 'Uploading file...';
    this.isUploading = true;

    this.stagingService.uploadModelFile(file, (progress) => {
      this.uploadProgress = progress;
      job.progress = Math.floor(progress * 0.3); // Upload is 30% of total
    }).subscribe({
      next: (response) => {
        this.isUploading = false;
        job.inputPath = response.filePath;
        job.progress = 30;
        job.message = 'File uploaded, starting conversion...';

        // Now convert the uploaded file
        this.executeConversion(job, response.filePath);
      },
      error: (err) => {
        this.isUploading = false;
        job.status = 'failed';
        job.error = err.message;
        job.message = 'Upload failed';
        this.isConverting = false;
        this.snackBar.open(`Upload failed: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
  }

  executeConversion(job: ConversionJob, inputPath?: string): void {
    const formValue = this.conversionForm.value;

    job.status = 'converting';
    job.message = 'Converting model...';

    const request = {
      inputPath: inputPath || this.getInputPath(),
      format: formValue.format,
      modelId: formValue.modelId,
      modelType: formValue.modelType,
      outputPath: formValue.outputPath || undefined,
      autoStage: formValue.autoStage,
      autoPromote: formValue.autoPromote,
      source: this.selectedSource,
      metadata: {
        description: formValue.description || undefined,
        embeddingDim: formValue.embeddingDim || undefined,
        maxSequenceLength: formValue.maxSequenceLength
      }
    };

    this.stagingService.convertModel(request).subscribe({
      next: (response) => {
        if (response.data) {
          this.pollConversionStatus(job.id, formValue.modelId);
        }
      },
      error: (err) => {
        job.status = 'failed';
        job.error = err.message;
        job.message = 'Conversion failed';
        this.isConverting = false;
        this.snackBar.open(`Conversion failed: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
  }

  pollConversionStatus(jobId: string, modelId: string): void {
    const job = this.conversionJobs.find(j => j.id === jobId);
    if (!job) return;

    this.pollSubscription = interval(1000)
      .pipe(
        takeWhile(() => job.status !== 'completed' && job.status !== 'failed')
      )
      .subscribe(() => {
        this.stagingService.getStagedModelStatus(modelId).subscribe({
          next: (status) => {
            // Map progress: upload was 0-30%, conversion is 30-100%
            const baseProgress = this.selectedSource === 'upload' ? 30 : 0;
            const scaledProgress = baseProgress + (status.progress * (100 - baseProgress) / 100);
            job.progress = Math.floor(scaledProgress);
            job.message = status.message || this.getStatusMessage(status.status);

            if (status.status === 'converting') {
              job.status = 'converting';
            } else if (status.status === 'validating') {
              job.status = 'validating';
            } else if (status.status === 'completed' || status.status === 'ready') {
              job.status = 'completed';
              job.completedAt = new Date();
              job.progress = 100;
              this.isConverting = false;
              this.saveJobs();
              this.snackBar.open('Model converted successfully!', 'Close', { duration: 3000 });
            } else if (status.status === 'failed') {
              job.status = 'failed';
              job.error = status.error;
              this.isConverting = false;
              this.saveJobs();
            }
          },
          error: () => {
            // Ignore polling errors
          }
        });
      });
  }

  getStatusMessage(status: string): string {
    switch (status) {
      case 'pending': return 'Waiting to start...';
      case 'uploading': return 'Uploading model file...';
      case 'downloading': return 'Downloading model files...';
      case 'converting': return 'Converting to SameDiff format...';
      case 'validating': return 'Validating converted model...';
      case 'ready': return 'Conversion complete, ready to promote';
      case 'completed': return 'Conversion completed successfully';
      case 'failed': return 'Conversion failed';
      default: return status;
    }
  }

  getStatusIcon(status: string): string {
    switch (status) {
      case 'pending': return 'hourglass_empty';
      case 'uploading': return 'cloud_upload';
      case 'converting': return 'transform';
      case 'validating': return 'fact_check';
      case 'completed': return 'check_circle';
      case 'failed': return 'error';
      default: return 'help';
    }
  }

  // ==================== Job Management ====================

  loadRecentJobs(): void {
    const saved = localStorage.getItem('conversionJobs');
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        this.conversionJobs = parsed.map((j: any) => ({
          ...j,
          startedAt: new Date(j.startedAt),
          completedAt: j.completedAt ? new Date(j.completedAt) : undefined
        }));
      } catch {
        this.conversionJobs = [];
      }
    }
  }

  saveJobs(): void {
    const toSave = this.conversionJobs.slice(0, 10);
    localStorage.setItem('conversionJobs', JSON.stringify(toSave));
  }

  clearHistory(): void {
    this.conversionJobs = [];
    localStorage.removeItem('conversionJobs');
    this.snackBar.open('Conversion history cleared', 'Close', { duration: 2000 });
  }

  removeJob(job: ConversionJob): void {
    const index = this.conversionJobs.indexOf(job);
    if (index > -1) {
      this.conversionJobs.splice(index, 1);
      this.saveJobs();
    }
  }

  formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    return `${(ms / 60000).toFixed(1)}m`;
  }

  resetForm(): void {
    this.conversionForm.reset({
      format: 'onnx',
      autoDetectFormat: true,
      modelType: 'dense_encoder',
      autoStage: true,
      autoPromote: false,
      maxSequenceLength: 512
    });
    this.selectedFile = null;
    this.selectedStagedModel = null;
    this.selectedDirectoryFile = null;
  }
}
