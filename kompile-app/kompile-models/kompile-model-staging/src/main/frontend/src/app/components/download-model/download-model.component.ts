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
import { ActivatedRoute } from '@angular/router';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { StagingService } from '../../services/staging.service';
import {
  HuggingFaceDiscovery,
  ImportDiagnosticEvent,
  ModelType,
  StageModelRequest,
  StagingModelInfo,
  StagingOutputFormat,
  StagingQuantizationProfile,
  StagingTargetProfile,
  TextModelAssetKey
} from '../../models/api-models';
import {
  buildTextAssetMap,
  buildTextAssetUrlMap,
  HTTPS_COMPONENT_SOURCE,
  invalidTextAssetUrls,
  missingLocalTextBundle,
  missingRemoteTextBundle,
  normalizeHuggingFaceReference,
  parseModelStagingPrefill,
  restoreImportDraft,
  retainSelectionOnPickerCancel,
  serializeImportDraft,
  TextBundleFiles
} from './text-model-import';

interface SourceOption {
  value: string;
  label: string;
  icon: string;
  placeholder: string;
  hint: string;
}

interface TargetOption {
  value: StagingTargetProfile;
  label: string;
  defaultSoc: string;
}

@Component({
  selector: 'app-download-model',
  standalone: false,
  templateUrl: './download-model.component.html',
  styleUrls: ['./download-model.component.css']
})
export class DownloadModelComponent implements OnInit, OnDestroy {

  private static readonly DRAFT_KEY = 'kompile.text-model-import.v3';
  private static readonly LEGACY_DRAFT_KEY = 'kompile.text-model-import.v2';
  private destroy$ = new Subject<void>();

  downloadForm: FormGroup;
  selectedFiles: TextBundleFiles = {};
  isDownloading = false;
  currentStaging: StagingModelInfo | null = null;
  completedOutputFormat: StagingOutputFormat | null = null;
  isDiscoveringHuggingFace = false;
  huggingFaceDiscovery: HuggingFaceDiscovery | null = null;
  importDiagnostics: ImportDiagnosticEvent[] = [];

  sources: SourceOption[] = [
    {
      value: 'huggingface',
      label: 'HuggingFace',
      icon: 'hub',
      placeholder: 'owner/repo or https://huggingface.co/owner/repo',
      hint: 'Paste an owner/repo, repository URL, or exact tree/blob/resolve URL'
    },
    {
      value: HTTPS_COMPONENT_SOURCE,
      label: 'Advanced HTTPS components',
      icon: 'link',
      placeholder: '',
      hint: 'Provide one complete public GGUF/GGML, tokenizer, and configuration bundle'
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
    },
    {
      value: 'local',
      label: 'Local bundle',
      icon: 'folder_open',
      placeholder: '',
      hint: 'Upload the model and every tokenizer/config companion atomically'
    }
  ];

  modelTypes: { value: ModelType; label: string }[] = [
    { value: 'dense_encoder', label: 'Dense Encoder (Semantic Retrieval)' },
    { value: 'sparse_encoder', label: 'Sparse Encoder (SPLADE, etc.)' },
    { value: 'cross_encoder', label: 'Cross-Encoder (Reranking)' },
    { value: 'llm_ggml', label: 'Local chat LLM (GGUF/GGML/SDX)' }
  ];

  formats: { value: string; label: string }[] = [
    { value: 'onnx', label: 'ONNX (.onnx)' },
    { value: 'gguf', label: 'GGUF (.gguf)' },
    { value: 'ggml', label: 'GGML (.ggml)' },
    { value: 'tensorflow', label: 'TensorFlow (.pb)' },
    { value: 'samediff', label: 'SameDiff / SDZ - No conversion needed' }
  ];

  outputFormats: { value: StagingOutputFormat; label: string }[] = [
    { value: 'model', label: 'Accelerator chat model (.sdz) — no knowledge graph' },
    { value: 'kproject', label: 'Full offline graph-chat project (.kproject)' }
  ];

  targets: TargetOption[] = [
    { value: 'android-arm64-vulkan', label: 'Android ARM64 · Vulkan 1.1', defaultSoc: 'Android_Vulkan_1_1' },
    { value: 'android-arm64-hexagon-htp', label: 'Android ARM64 · Qualcomm Hexagon HTP', defaultSoc: 'SM8650' },
    { value: 'android-arm64-nnapi-accelerator', label: 'Pixel 8a / Tensor G3 · NNAPI accelerator', defaultSoc: 'Tensor_G3' },
    { value: 'android-arm64-google-tensor-g5', label: 'Google Tensor G5 · Android ARM64', defaultSoc: 'Tensor_G5' }
  ];

  quantizationProfiles: { value: StagingQuantizationProfile; label: string }[] = [
    { value: 'none', label: 'None' },
    { value: 'int8', label: 'INT8 (target optimized)' }
  ];

  // Recent downloads for quick access
  recentDownloads: StagingModelInfo[] = [];

  constructor(
    private fb: FormBuilder,
    private stagingService: StagingService,
    private snackBar: MatSnackBar,
    private route: ActivatedRoute
  ) {
    this.downloadForm = this.fb.group({
      source: ['huggingface', Validators.required],
      repository: ['', Validators.required],
      modelId: ['', Validators.required],
      modelType: ['llm_ggml', Validators.required],
      format: ['gguf', Validators.required],
      outputFormat: ['model', Validators.required],
      targetProfile: ['android-arm64-vulkan', Validators.required],
      quantizationProfile: ['none', Validators.required],
      targetSoc: ['Android_Vulkan_1_1', Validators.required],
      autoPromote: [false],
      hfToken: [''],
      revision: [''],
      modelPath: [''],
      tokenizerPath: [''],
      tokenizerConfigPath: [''],
      specialTokensMapPath: [''],
      addedTokensPath: [''],
      chatTemplatePath: [''],
      generationConfigPath: [''],
      modelConfigPath: [''],
      textGenerationPath: [''],
      modelUrl: [''],
      tokenizerUrl: [''],
      tokenizerConfigUrl: [''],
      specialTokensMapUrl: [''],
      addedTokensUrl: [''],
      chatTemplateUrl: [''],
      generationConfigUrl: [''],
      modelConfigUrl: [''],
      textGenerationUrl: ['']
    });
  }

  ngOnInit(): void {
    this.restoreDraft();
    this.applyAndroidPreset();
    this.configureSource(this.downloadForm.get('source')?.value);
    this.loadRecentDownloads();
    this.loadImportDiagnostics();

    this.downloadForm.get('targetProfile')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(target => this.applyTargetSocDefault(target));

    this.downloadForm.get('source')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(source => {
        this.configureSource(source);
        this.updateRepositoryPlaceholder(source);
        this.huggingFaceDiscovery = null;
      });

    this.downloadForm.get('format')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(format => this.applyModelPathDefault(format));

    this.downloadForm.get('outputFormat')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(output => {
        if (output === 'model' || output === 'kproject') {
          this.downloadForm.patchValue({ modelType: 'llm_ggml' }, { emitEvent: false });
        }
      });

    this.downloadForm.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(value => this.persistDraft(value));
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

  isLocalSource(): boolean {
    return this.downloadForm.get('source')?.value === 'local';
  }

  isRunnableTextOutput(): boolean {
    const output = this.downloadForm.get('outputFormat')?.value;
    return (output === 'model' || output === 'kproject')
      && Boolean(this.downloadForm.get('targetProfile')?.value);
  }

  isHuggingFaceSource(): boolean {
    return this.downloadForm.get('source')?.value === 'huggingface';
  }

  isComponentUrlSource(): boolean {
    return this.downloadForm.get('source')?.value === HTTPS_COMPONENT_SOURCE;
  }

  clearHuggingFaceDiscovery(): void {
    this.huggingFaceDiscovery = null;
  }

  discoverHuggingFace(): void {
    const value = this.downloadForm.getRawValue();
    const reference = normalizeHuggingFaceReference(value.repository);
    if (!reference) {
      this.showError(
        'Enter a public Hugging Face owner/repo or canonical HTTPS repository/tree/blob URL.'
      );
      return;
    }

    this.isDiscoveringHuggingFace = true;
    this.stagingService.discoverHuggingFace({
      reference,
      revision: value.revision || undefined,
      authToken: value.hfToken || undefined
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: discovery => {
        this.isDiscoveringHuggingFace = false;
        this.huggingFaceDiscovery = discovery;
        this.applyHuggingFaceDiscovery(discovery);
        if (discovery.requiresModelSelection) {
          this.showSuccess(
            `Resolved ${discovery.resolvedRevision.slice(0, 12)}. Select one of `
              + `${discovery.modelCandidates.length} GGUF/GGML files.`
          );
        } else {
          this.showSuccess(
            `Resolved and pinned Hugging Face commit `
              + `${discovery.resolvedRevision.slice(0, 12)}.`
          );
        }
      },
      error: error => {
        this.isDiscoveringHuggingFace = false;
        this.showError(`Hugging Face discovery failed: ${error.message}`);
      }
    });
  }

  selectHuggingFaceModel(path: string): void {
    const candidate = this.huggingFaceDiscovery?.modelCandidates
      .find(model => model.path === path);
    if (!candidate) {
      return;
    }
    this.downloadForm.patchValue({
      modelPath: candidate.path,
      format: candidate.format,
      modelType: 'llm_ggml'
    });
  }

  private applyHuggingFaceDiscovery(discovery: HuggingFaceDiscovery): void {
    const assets = discovery.discoveredAssets || {};
    const selected = discovery.modelCandidates.find(
      candidate => candidate.path === assets.model
    );
    this.downloadForm.patchValue({
      repository: discovery.repository,
      revision: discovery.resolvedRevision,
      modelPath: assets.model || '',
      tokenizerPath: assets.tokenizer || '',
      tokenizerConfigPath: assets.tokenizerConfig || '',
      specialTokensMapPath: assets.specialTokensMap || '',
      addedTokensPath: assets.addedTokens || '',
      chatTemplatePath: assets.chatTemplate || '',
      generationConfigPath: assets.generationConfig || '',
      modelConfigPath: assets.modelConfig || '',
      textGenerationPath: assets.textGeneration || '',
      format: selected?.format || this.downloadForm.get('format')?.value,
      modelType: 'llm_ggml'
    }, { emitEvent: false });
    if (!this.downloadForm.get('modelId')?.value) {
      this.downloadForm.patchValue({
        modelId: discovery.repository.split('/')[1].replace(/[^a-zA-Z0-9_-]/g, '-')
          .toLowerCase()
      });
    }
  }

  onFileSelected(key: TextModelAssetKey, event: Event): void {
    const input = event.target as HTMLInputElement;
    const selected = input.files?.item(0) ?? undefined;
    const retained = retainSelectionOnPickerCancel(this.selectedFiles[key], selected);
    this.selectedFiles = { ...this.selectedFiles, [key]: retained };
    input.value = '';
  }

  selectedFileName(key: TextModelAssetKey): string {
    return this.selectedFiles[key]?.name ?? 'Choose file';
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

    const formValue = this.downloadForm.getRawValue();
    const local = formValue.source === 'local';
    const componentSource = formValue.source === HTTPS_COMPONENT_SOURCE;
    let remoteRepository = formValue.repository;
    if (local) {
      const missing = missingLocalTextBundle(this.selectedFiles);
      if (missing.length > 0) {
        this.showError(`Select a complete local bundle. Missing: ${missing.join(', ')}`);
        return;
      }
    } else if (formValue.source === 'huggingface') {
      const reference = normalizeHuggingFaceReference(formValue.repository);
      if (!reference) {
        this.showError(
          'Enter a public Hugging Face owner/repo or canonical HTTPS repository/tree/blob URL.'
        );
        return;
      }
      remoteRepository = reference;
      const invalidUrls = invalidTextAssetUrls(formValue);
      if (invalidUrls.length > 0) {
        this.showError(
          `Component URLs must be public HTTPS URLs without credentials or query strings: `
            + invalidUrls.join(', ')
        );
        return;
      }
      if (this.huggingFaceDiscovery?.requiresModelSelection
          && !formValue.modelPath
          && !formValue.modelUrl) {
        this.showError('Select one discovered GGUF/GGML file before staging.');
        return;
      }
    } else if (componentSource) {
      const invalidUrls = invalidTextAssetUrls(formValue);
      if (invalidUrls.length > 0) {
        this.showError(
          `Component URLs must be public HTTPS URLs without credentials, escaping, `
            + `or query strings: ${invalidUrls.join(', ')}`
        );
        return;
      }
      const missing = missingRemoteTextBundle(formValue);
      if (missing.length > 0) {
        this.showError(`Complete the public HTTPS component bundle. Missing: ${missing.join(', ')}`);
        return;
      }
      remoteRepository = '';
    }

    this.isDownloading = true;
    this.completedOutputFormat = formValue.outputFormat;
    const textAssets = local || componentSource ? undefined : buildTextAssetMap(formValue);
    const textAssetUrls = local ? undefined : buildTextAssetUrlMap(formValue);

    const request: StageModelRequest = {
      modelId: formValue.modelId,
      source: formValue.source,
      repository: local ? 'multipart-upload' : remoteRepository,
      format: formValue.format,
      type: formValue.modelType,
      // Targeted mobile artifacts are downloaded and imported explicitly on-device.
      autoPromote: false,
      revision: formValue.revision || undefined,
      authToken: formValue.source === 'huggingface'
        ? formValue.hfToken || undefined
        : undefined,
      textAssets: textAssets && Object.keys(textAssets).length > 0 ? textAssets : undefined,
      textAssetUrls: textAssetUrls && Object.keys(textAssetUrls).length > 0
        ? textAssetUrls
        : undefined,
      outputFormat: formValue.outputFormat,
      targetProfile: formValue.targetProfile,
      quantizationProfile: formValue.quantizationProfile,
      targetSoc: formValue.targetSoc
    };

    const staging = local
      ? this.stagingService.stageTextBundle(request, this.selectedFiles)
      : this.stagingService.stageModel(request);
    staging
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.currentStaging = response;
          this.showSuccess(`Started staging ${formValue.modelId}`);
          this.loadImportDiagnostics();
          this.pollStagingStatus(formValue.modelId);
        },
        error: (error) => {
          this.isDownloading = false;
          this.loadImportDiagnostics();
          this.showError(`Failed to start staging: ${error.message}`);
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
            this.loadImportDiagnostics();

            if (status.status === 'completed') {
              this.showSuccess(`Model ${modelId} staged successfully!`);
              this.loadRecentDownloads();
              this.resetInputFields();
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

  downloadOutput(modelId: string): void {
    this.stagingService.downloadStagedOutput(modelId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          const disposition = response.headers.get('Content-Disposition');
          const filename = this.getOutputFilename(disposition, modelId);
          const url = URL.createObjectURL(response.body as Blob);
          const link = document.createElement('a');
          link.href = url;
          link.download = filename;
          link.click();
          URL.revokeObjectURL(url);
        },
        error: error => this.showError(`Failed to download output: ${error.message}`)
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

  loadImportDiagnostics(): void {
    this.stagingService.getImportDiagnostics(30)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: events => {
          this.importDiagnostics = events;
        },
        error: () => {
          // Diagnostics never block the import flow.
        }
      });
  }

  diagnosticIcon(event: ImportDiagnosticEvent): string {
    if (event.severity === 'error') {
      return 'error';
    }
    return event.code === 'import.complete' ? 'check_circle' : 'info';
  }

  diagnosticDetails(event: ImportDiagnosticEvent): Array<{ key: string; value: string }> {
    return Object.entries(event.details || {}).map(([key, value]) => ({ key, value }));
  }

  resetForm(): void {
    this.resetInputFields();
    this.currentStaging = null;
    this.completedOutputFormat = null;
    this.clearDraft();
  }

  clearCompleted(): void {
    this.currentStaging = null;
    this.completedOutputFormat = null;
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

  private resetInputFields(): void {
    this.selectedFiles = {};
    this.downloadForm.patchValue({
      repository: '',
      modelId: '',
      autoPromote: false,
      hfToken: '',
      revision: '',
      modelPath: '',
      tokenizerPath: '',
      tokenizerConfigPath: '',
      specialTokensMapPath: '',
      addedTokensPath: '',
      chatTemplatePath: '',
      generationConfigPath: '',
      modelConfigPath: '',
      textGenerationPath: '',
      modelUrl: '',
      tokenizerUrl: '',
      tokenizerConfigUrl: '',
      specialTokensMapUrl: '',
      addedTokensUrl: '',
      chatTemplateUrl: '',
      generationConfigUrl: '',
      modelConfigUrl: '',
      textGenerationUrl: ''
    });
    this.huggingFaceDiscovery = null;
  }

  private configureSource(source: string): void {
    const repository = this.downloadForm.get('repository');
    if (source === 'local' || source === HTTPS_COMPONENT_SOURCE) {
      repository?.clearValidators();
      this.downloadForm.patchValue({
        repository: '',
        modelType: 'llm_ggml'
      }, { emitEvent: false });
    } else {
      repository?.setValidators(Validators.required);
    }
    repository?.updateValueAndValidity({ emitEvent: false });
  }

  private applyModelPathDefault(format: string): void {
    if (this.isLocalSource() || this.isHuggingFaceSource() || this.isComponentUrlSource()) {
      return;
    }
    const defaults = [
      'onnx/model.onnx',
      'model.gguf',
      'model.ggml',
      'model.sdz'
    ];
    const current = this.downloadForm.get('modelPath')?.value;
    if (current && !defaults.includes(current)) {
      return;
    }
    const modelPath = format === 'gguf'
      ? 'model.gguf'
      : format === 'ggml'
        ? 'model.ggml'
        : format === 'samediff'
          ? 'model.sdz'
          : 'onnx/model.onnx';
    this.downloadForm.patchValue({ modelPath }, { emitEvent: false });
  }

  private persistDraft(value: Record<string, unknown>): void {
    if (typeof localStorage === 'undefined') {
      return;
    }
    localStorage.setItem(
      DownloadModelComponent.DRAFT_KEY,
      serializeImportDraft(value)
    );
  }

  private restoreDraft(): void {
    if (typeof localStorage === 'undefined') {
      return;
    }
    localStorage.removeItem(DownloadModelComponent.LEGACY_DRAFT_KEY);
    const restored = restoreImportDraft(
      localStorage.getItem(DownloadModelComponent.DRAFT_KEY)
    );
    if (restored) {
      this.downloadForm.patchValue(restored, { emitEvent: false });
    }
  }

  private clearDraft(): void {
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(DownloadModelComponent.DRAFT_KEY);
    }
  }

  private applyAndroidPreset(): void {
    const fragment = typeof window !== 'undefined'
      ? window.location.hash
      : this.route.snapshot.fragment;
    const prefill = parseModelStagingPrefill(fragment);
    if (fragment && typeof window !== 'undefined' && window.history?.replaceState) {
      // Component URLs are a one-shot browser handoff and must not remain in history.
      window.history.replaceState(
        null,
        '',
        `${window.location.pathname}${window.location.search}`
      );
    }
    if (prefill) {
      this.downloadForm.patchValue(prefill, { emitEvent: false });
    } else if (fragment) {
      this.showError(
        'The model handoff was incomplete or invalid. Paste a Hugging Face URL, or provide the model, tokenizer, tokenizer configuration, and model configuration URLs.'
      );
    }

    const artifact = this.route.snapshot.queryParamMap.get('artifact');
    const target = this.route.snapshot.queryParamMap.get('target') as StagingTargetProfile | null;
    const requestedOutput: StagingOutputFormat | null = artifact === 'kproject'
      ? 'kproject'
      : artifact === 'model' || artifact === 'sdz'
        ? 'model'
        : null;
    if (requestedOutput && target && this.targets.some(option => option.value === target)) {
      this.downloadForm.patchValue({
        outputFormat: requestedOutput,
        modelType: 'llm_ggml',
        targetProfile: target,
        targetSoc: this.targets.find(option => option.value === target)?.defaultSoc
      });
    }
  }

  private applyTargetSocDefault(target: StagingTargetProfile): void {
    const option = this.targets.find(candidate => candidate.value === target);
    if (option) {
      this.downloadForm.patchValue({ targetSoc: option.defaultSoc });
    }
  }

  private getOutputFilename(contentDisposition: string | null, modelId: string): string {
    const utf8Match = contentDisposition?.match(/filename\*=UTF-8''([^;]+)/i);
    if (utf8Match) {
      return decodeURIComponent(utf8Match[1]);
    }
    const filenameMatch = contentDisposition?.match(/filename="?([^";]+)"?/i);
    const extension = this.completedOutputFormat === 'kproject' ? '.kproject' : '.sdz';
    return filenameMatch?.[1] || `${modelId}${extension}`;
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
