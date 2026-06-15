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
import { CompilerService, GraphInfoResponse, GraphAnalysisInfo, OpInfo, LayerGroupInfo } from '../../services/compiler.service';

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
  preprocessorForm: FormGroup;

  // Edit state
  isEditingMetadata = false;
  isEditingTokenizer = false;
  isEditingPreprocessor = false;
  isSaving = false;
  isDeleting = false;
  isProbing = false;

  // DSP Execution Plan state
  executionPlanLoaded = false;
  executionPlanLoading = false;
  executionPlanError: string | null = null;
  graphInfo: GraphInfoResponse | null = null;
  executionPlanStages: ExecutionPlanStage[] = [];

  // Model options
  modelTypes: { value: ModelType; label: string }[] = [
    { value: 'dense_encoder', label: 'Dense Encoder' },
    { value: 'sparse_encoder', label: 'Sparse Encoder' },
    { value: 'cross_encoder', label: 'Cross-Encoder (Reranker)' },
    { value: 'encoder', label: 'Encoder (Legacy)' },
    { value: 'vlm_pipeline', label: 'VLM Pipeline' },
    { value: 'ocr_detection', label: 'OCR Detection' },
    { value: 'ocr_recognition', label: 'OCR Recognition' },
    { value: 'ocr_table', label: 'OCR Table' },
    { value: 'ocr_pipeline', label: 'OCR Pipeline' },
    { value: 'layout_model', label: 'Layout Model' },
    { value: 'document_classifier', label: 'Document Classifier' },
    { value: 'llm_ggml', label: 'LLM (GGML)' }
  ];

  modelStatuses: { value: ModelStatus; label: string }[] = [
    { value: 'active', label: 'Active' },
    { value: 'staged', label: 'Staged' },
    { value: 'deprecated', label: 'Deprecated' }
  ];

  // Preprocessor presets
  selectedPreprocessorPreset: string = '';
  preprocessorPresets: { id: string; name: string; description: string; config: any }[] = [
    {
      id: 'clip',
      name: 'CLIP / ViT',
      description: 'OpenAI CLIP and ViT-based models (224x224, ImageNet norm)',
      config: {
        image_processor_type: 'CLIPImageProcessor',
        do_resize: true, size_height: 224, size_width: 224,
        size_shortest_edge: null, size_longest_edge: null, resample: 3,
        do_rescale: true, rescale_factor: 1.0 / 255.0,
        do_normalize: true,
        image_mean: '0.48145466, 0.4578275, 0.40821073',
        image_std: '0.26862954, 0.26130258, 0.27577711',
        do_convert_rgb: true,
        do_center_crop: true, crop_size_height: 224, crop_size_width: 224,
        do_pad: false, pad_size_height: null, pad_size_width: null,
        patch_size: 16, num_channels: 3
      }
    },
    {
      id: 'siglip',
      name: 'SigLIP',
      description: 'Google SigLIP models (384x384, custom norm)',
      config: {
        image_processor_type: 'SiglipImageProcessor',
        do_resize: true, size_height: 384, size_width: 384,
        size_shortest_edge: null, size_longest_edge: null, resample: 3,
        do_rescale: true, rescale_factor: 1.0 / 255.0,
        do_normalize: true,
        image_mean: '0.5, 0.5, 0.5',
        image_std: '0.5, 0.5, 0.5',
        do_convert_rgb: true,
        do_center_crop: false, crop_size_height: null, crop_size_width: null,
        do_pad: false, pad_size_height: null, pad_size_width: null,
        patch_size: 16, num_channels: 3
      }
    },
    {
      id: 'florence2',
      name: 'Florence-2',
      description: 'Microsoft Florence-2 VLM (768x768, ImageNet norm)',
      config: {
        image_processor_type: 'CLIPImageProcessor',
        do_resize: true, size_height: 768, size_width: 768,
        size_shortest_edge: null, size_longest_edge: null, resample: 3,
        do_rescale: true, rescale_factor: 1.0 / 255.0,
        do_normalize: true,
        image_mean: '0.485, 0.456, 0.406',
        image_std: '0.229, 0.224, 0.225',
        do_convert_rgb: true,
        do_center_crop: false, crop_size_height: null, crop_size_width: null,
        do_pad: false, pad_size_height: null, pad_size_width: null,
        patch_size: 16, num_channels: 3
      }
    },
    {
      id: 'imagenet',
      name: 'ImageNet Standard',
      description: 'Standard ImageNet preprocessing (224x224, ImageNet norm)',
      config: {
        image_processor_type: 'ImageProcessor',
        do_resize: true, size_height: 224, size_width: 224,
        size_shortest_edge: 256, size_longest_edge: null, resample: 3,
        do_rescale: true, rescale_factor: 1.0 / 255.0,
        do_normalize: true,
        image_mean: '0.485, 0.456, 0.406',
        image_std: '0.229, 0.224, 0.225',
        do_convert_rgb: true,
        do_center_crop: true, crop_size_height: 224, crop_size_width: 224,
        do_pad: false, pad_size_height: null, pad_size_width: null,
        patch_size: null, num_channels: 3
      }
    },
    {
      id: 'donut',
      name: 'Donut / Nougat',
      description: 'Document understanding models (2560x1920, no norm)',
      config: {
        image_processor_type: 'DonutImageProcessor',
        do_resize: true, size_height: 2560, size_width: 1920,
        size_shortest_edge: null, size_longest_edge: null, resample: 3,
        do_rescale: true, rescale_factor: 1.0 / 255.0,
        do_normalize: false,
        image_mean: '', image_std: '',
        do_convert_rgb: true,
        do_center_crop: false, crop_size_height: null, crop_size_width: null,
        do_pad: true, pad_size_height: 2560, pad_size_width: 1920,
        patch_size: null, num_channels: 3
      }
    },
    {
      id: 'dit',
      name: 'DiT / BEiT',
      description: 'Document Image Transformer and BEiT models (224x224, ImageNet norm)',
      config: {
        image_processor_type: 'BeitImageProcessor',
        do_resize: true, size_height: 224, size_width: 224,
        size_shortest_edge: null, size_longest_edge: null, resample: 3,
        do_rescale: true, rescale_factor: 1.0 / 255.0,
        do_normalize: true,
        image_mean: '0.5, 0.5, 0.5',
        image_std: '0.5, 0.5, 0.5',
        do_convert_rgb: true,
        do_center_crop: true, crop_size_height: 224, crop_size_width: 224,
        do_pad: false, pad_size_height: null, pad_size_width: null,
        patch_size: 16, num_channels: 3
      }
    },
    {
      id: 'raw',
      name: 'Raw (No Processing)',
      description: 'Pass-through with only RGB conversion, no resize/norm',
      config: {
        image_processor_type: '',
        do_resize: false, size_height: null, size_width: null,
        size_shortest_edge: null, size_longest_edge: null, resample: null,
        do_rescale: false, rescale_factor: null,
        do_normalize: false,
        image_mean: '', image_std: '',
        do_convert_rgb: true,
        do_center_crop: false, crop_size_height: null, crop_size_width: null,
        do_pad: false, pad_size_height: null, pad_size_width: null,
        patch_size: null, num_channels: 3
      }
    }
  ];

  constructor(
    public dialogRef: MatDialogRef<ModelDetailsDialogComponent, ModelDetailsDialogResult>,
    @Inject(MAT_DIALOG_DATA) public data: ModelDetailsDialogData,
    private fb: FormBuilder,
    private stagingService: StagingService,
    private compilerService: CompilerService,
    private snackBar: MatSnackBar
  ) {
    this.model = data.model;
    this.editable = data.editable ?? false;

    // Initialize metadata form
    const meta = this.model.metadata;
    this.metadataForm = this.fb.group({
      type: [this.model.type],
      status: [this.model.status],
      description: [meta?.description || ''],
      embeddingDim: [meta?.embeddingDim],
      hiddenSize: [meta?.hiddenSize],
      numLayers: [meta?.numLayers],
      maxSequenceLength: [meta?.maxSequenceLength],
      framework: [meta?.framework || ''],
      trainingData: [meta?.trainingData || ''],
      sourceOrigin: [meta?.sourceOrigin || ''],
      sourceRepository: [meta?.sourceRepository || ''],
      vocabSize: [meta?.vocabSize],
      // Pipeline identity
      encoderType: [meta?.encoderType || ''],
      ragRole: [meta?.ragRole || ''],
      version: [meta?.version || ''],
      // OCR fields
      inputHeight: [meta?.inputHeight],
      inputWidth: [meta?.inputWidth],
      supportedLanguages: [meta?.supportedLanguages?.join(', ') || ''],
      supportsBatch: [meta?.supportsBatch ?? true],
      maxBatchSize: [meta?.maxBatchSize],
      supportsHandwriting: [meta?.supportsHandwriting ?? false],
      averageAccuracy: [meta?.averageAccuracy],
      ocrVocabSize: [meta?.ocrVocabSize],
      usesCtc: [meta?.usesCtc ?? false],
      // VLM fields
      visionFrames: [meta?.visionFrames],
      imageSize: [meta?.imageSize],
      tileSize: [meta?.tileSize],
      components: [meta?.components?.join(', ') || ''],
      // Vision encoder IO config
      visionEncoderPixelValuesName: [meta?.vision_encoder_pixel_values_name || ''],
      visionEncoderPixelAttentionMaskName: [meta?.vision_encoder_pixel_attention_mask_name || ''],
      visionEncoderPrimaryOutputName: [meta?.vision_encoder_primary_output_name || ''],
      visionEncoderOutputNames: [meta?.vision_encoder_output_names?.join(', ') || '']
    });

    // Initialize tokenizer form
    this.tokenizerForm = this.fb.group({
      do_lower_case: [this.model.tokenizer?.do_lower_case ?? true],
      add_special_tokens: [this.model.tokenizer?.add_special_tokens ?? true],
      strip_accents: [this.model.tokenizer?.strip_accents ?? false],
      max_length: [this.model.tokenizer?.max_length ?? 512, [Validators.min(1), Validators.max(8192)]],
      padding: [this.model.tokenizer?.padding || ''],
      truncation: [this.model.tokenizer?.truncation ?? false]
    });

    // Initialize preprocessor form
    const pp = this.model.preprocessor;
    this.preprocessorForm = this.fb.group({
      image_processor_type: [pp?.image_processor_type || ''],
      do_resize: [pp?.do_resize ?? true],
      size_height: [pp?.size_height],
      size_width: [pp?.size_width],
      size_shortest_edge: [pp?.size_shortest_edge],
      size_longest_edge: [pp?.size_longest_edge],
      resample: [pp?.resample],
      do_rescale: [pp?.do_rescale ?? true],
      rescale_factor: [pp?.rescale_factor ?? (1.0 / 255.0)],
      do_normalize: [pp?.do_normalize ?? true],
      image_mean: [pp?.image_mean ? pp.image_mean.join(', ') : ''],
      image_std: [pp?.image_std ? pp.image_std.join(', ') : ''],
      do_convert_rgb: [pp?.do_convert_rgb ?? true],
      do_center_crop: [pp?.do_center_crop ?? false],
      crop_size_height: [pp?.crop_size_height],
      crop_size_width: [pp?.crop_size_width],
      do_pad: [pp?.do_pad ?? false],
      pad_size_height: [pp?.pad_size_height],
      pad_size_width: [pp?.pad_size_width],
      patch_size: [pp?.patch_size],
      num_channels: [pp?.num_channels ?? 3, [Validators.min(1), Validators.max(4)]]
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
    const meta = this.model.metadata;
    this.metadataForm.patchValue({
      type: this.model.type,
      status: this.model.status,
      description: meta?.description || '',
      embeddingDim: meta?.embeddingDim,
      hiddenSize: meta?.hiddenSize,
      numLayers: meta?.numLayers,
      maxSequenceLength: meta?.maxSequenceLength,
      framework: meta?.framework || '',
      trainingData: meta?.trainingData || '',
      sourceOrigin: meta?.sourceOrigin || '',
      sourceRepository: meta?.sourceRepository || '',
      vocabSize: meta?.vocabSize,
      encoderType: meta?.encoderType || '',
      ragRole: meta?.ragRole || '',
      version: meta?.version || '',
      inputHeight: meta?.inputHeight,
      inputWidth: meta?.inputWidth,
      supportedLanguages: meta?.supportedLanguages?.join(', ') || '',
      supportsBatch: meta?.supportsBatch ?? true,
      maxBatchSize: meta?.maxBatchSize,
      supportsHandwriting: meta?.supportsHandwriting ?? false,
      averageAccuracy: meta?.averageAccuracy,
      ocrVocabSize: meta?.ocrVocabSize,
      usesCtc: meta?.usesCtc ?? false,
      visionFrames: meta?.visionFrames,
      imageSize: meta?.imageSize,
      tileSize: meta?.tileSize,
      components: meta?.components?.join(', ') || '',
      visionEncoderPixelValuesName: meta?.vision_encoder_pixel_values_name || '',
      visionEncoderPixelAttentionMaskName: meta?.vision_encoder_pixel_attention_mask_name || '',
      visionEncoderPrimaryOutputName: meta?.vision_encoder_primary_output_name || '',
      visionEncoderOutputNames: meta?.vision_encoder_output_names?.join(', ') || ''
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
        vocabSize: formValue.vocabSize || undefined,
        // Pipeline identity
        encoderType: formValue.encoderType || undefined,
        ragRole: formValue.ragRole || undefined,
        version: formValue.version || undefined,
        // OCR fields
        inputHeight: formValue.inputHeight || undefined,
        inputWidth: formValue.inputWidth || undefined,
        supportedLanguages: formValue.supportedLanguages
          ? formValue.supportedLanguages.split(',').map((s: string) => s.trim()).filter((s: string) => s)
          : undefined,
        supportsBatch: formValue.supportsBatch,
        maxBatchSize: formValue.maxBatchSize || undefined,
        supportsHandwriting: formValue.supportsHandwriting,
        averageAccuracy: formValue.averageAccuracy || undefined,
        ocrVocabSize: formValue.ocrVocabSize || undefined,
        usesCtc: formValue.usesCtc,
        // VLM fields
        visionFrames: formValue.visionFrames || undefined,
        imageSize: formValue.imageSize || undefined,
        tileSize: formValue.tileSize || undefined,
        components: formValue.components
          ? formValue.components.split(',').map((s: string) => s.trim()).filter((s: string) => s)
          : undefined,
        // Vision encoder IO
        visionEncoderPixelValuesName: formValue.visionEncoderPixelValuesName || undefined,
        visionEncoderPixelAttentionMaskName: formValue.visionEncoderPixelAttentionMaskName || undefined,
        visionEncoderPrimaryOutputName: formValue.visionEncoderPrimaryOutputName || undefined,
        visionEncoderOutputNames: formValue.visionEncoderOutputNames
          ? formValue.visionEncoderOutputNames.split(',').map((s: string) => s.trim()).filter((s: string) => s)
          : undefined
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
      max_length: this.model.tokenizer?.max_length ?? 512,
      padding: this.model.tokenizer?.padding || '',
      truncation: this.model.tokenizer?.truncation ?? false
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
        maxLength: formValue.max_length,
        padding: formValue.padding || undefined,
        truncation: formValue.truncation
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
            max_length: formValue.max_length,
            padding: formValue.padding || undefined,
            truncation: formValue.truncation
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

  // ==================== Preprocessor Editing ====================

  togglePreprocessorEdit(): void {
    this.isEditingPreprocessor = !this.isEditingPreprocessor;
    if (!this.isEditingPreprocessor) {
      this.resetPreprocessorForm();
    }
  }

  resetPreprocessorForm(): void {
    const pp = this.model.preprocessor;
    this.preprocessorForm.patchValue({
      image_processor_type: pp?.image_processor_type || '',
      do_resize: pp?.do_resize ?? true,
      size_height: pp?.size_height,
      size_width: pp?.size_width,
      size_shortest_edge: pp?.size_shortest_edge,
      size_longest_edge: pp?.size_longest_edge,
      resample: pp?.resample,
      do_rescale: pp?.do_rescale ?? true,
      rescale_factor: pp?.rescale_factor ?? (1.0 / 255.0),
      do_normalize: pp?.do_normalize ?? true,
      image_mean: pp?.image_mean ? pp.image_mean.join(', ') : '',
      image_std: pp?.image_std ? pp.image_std.join(', ') : '',
      do_convert_rgb: pp?.do_convert_rgb ?? true,
      do_center_crop: pp?.do_center_crop ?? false,
      crop_size_height: pp?.crop_size_height,
      crop_size_width: pp?.crop_size_width,
      do_pad: pp?.do_pad ?? false,
      pad_size_height: pp?.pad_size_height,
      pad_size_width: pp?.pad_size_width,
      patch_size: pp?.patch_size,
      num_channels: pp?.num_channels ?? 3
    });
  }

  private parseFloatArray(value: string): number[] | undefined {
    if (!value || !value.trim()) return undefined;
    const nums = value.split(',').map(s => parseFloat(s.trim())).filter(n => !isNaN(n));
    return nums.length > 0 ? nums : undefined;
  }

  savePreprocessorConfig(): void {
    if (this.preprocessorForm.invalid) return;

    this.isSaving = true;
    const fv = this.preprocessorForm.value;

    const updates = {
      preprocessor: {
        imageProcessorType: fv.image_processor_type || undefined,
        doResize: fv.do_resize,
        sizeHeight: fv.size_height || undefined,
        sizeWidth: fv.size_width || undefined,
        sizeShortestEdge: fv.size_shortest_edge || undefined,
        sizeLongestEdge: fv.size_longest_edge || undefined,
        resample: fv.resample || undefined,
        doRescale: fv.do_rescale,
        rescaleFactor: fv.rescale_factor || undefined,
        doNormalize: fv.do_normalize,
        imageMean: this.parseFloatArray(fv.image_mean),
        imageStd: this.parseFloatArray(fv.image_std),
        doConvertRgb: fv.do_convert_rgb,
        doCenterCrop: fv.do_center_crop,
        cropSizeHeight: fv.crop_size_height || undefined,
        cropSizeWidth: fv.crop_size_width || undefined,
        doPad: fv.do_pad,
        padSizeHeight: fv.pad_size_height || undefined,
        padSizeWidth: fv.pad_size_width || undefined,
        patchSize: fv.patch_size || undefined,
        numChannels: fv.num_channels || undefined
      }
    };

    this.stagingService.updateModel(this.model.model_id, updates).subscribe({
      next: (response: any) => {
        this.isSaving = false;
        if (response.success) {
          this.model.preprocessor = {
            image_processor_type: fv.image_processor_type || undefined,
            do_resize: fv.do_resize,
            size_height: fv.size_height || undefined,
            size_width: fv.size_width || undefined,
            size_shortest_edge: fv.size_shortest_edge || undefined,
            size_longest_edge: fv.size_longest_edge || undefined,
            resample: fv.resample || undefined,
            do_rescale: fv.do_rescale,
            rescale_factor: fv.rescale_factor || undefined,
            do_normalize: fv.do_normalize,
            image_mean: this.parseFloatArray(fv.image_mean),
            image_std: this.parseFloatArray(fv.image_std),
            do_convert_rgb: fv.do_convert_rgb,
            do_center_crop: fv.do_center_crop,
            crop_size_height: fv.crop_size_height || undefined,
            crop_size_width: fv.crop_size_width || undefined,
            do_pad: fv.do_pad,
            pad_size_height: fv.pad_size_height || undefined,
            pad_size_width: fv.pad_size_width || undefined,
            patch_size: fv.patch_size || undefined,
            num_channels: fv.num_channels || undefined
          };

          this.isEditingPreprocessor = false;
          this.snackBar.open('Preprocessor configuration updated successfully', 'Close', {
            duration: 3000,
            panelClass: ['snackbar-success']
          });
        } else {
          this.snackBar.open(response.error || 'Failed to update preprocessor', 'Close', {
            duration: 5000,
            panelClass: ['snackbar-error']
          });
        }
      },
      error: (err) => {
        this.isSaving = false;
        this.snackBar.open('Failed to update preprocessor: ' + err.message, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  applyPreprocessorPreset(presetId: string): void {
    const preset = this.preprocessorPresets.find(p => p.id === presetId);
    if (!preset) return;
    this.selectedPreprocessorPreset = presetId;
    this.preprocessorForm.patchValue(preset.config);
    this.snackBar.open(`Applied "${preset.name}" preset`, 'Close', {
      duration: 2000
    });
  }

  hasPreprocessorConfig(): boolean {
    return !!this.model.preprocessor;
  }

  getPreprocessorItems(): { label: string; value: any; icon: string }[] {
    const pp = this.model.preprocessor;
    if (!pp) return [];

    const items: { label: string; value: any; icon: string }[] = [];

    if (pp.image_processor_type) {
      items.push({ label: 'Processor Type', value: pp.image_processor_type, icon: 'image' });
    }
    items.push({ label: 'Resize', value: pp.do_resize ? 'Yes' : 'No', icon: 'photo_size_select_large' });
    if (pp.size_height || pp.size_width) {
      items.push({ label: 'Size (H x W)', value: `${pp.size_height || '?'} x ${pp.size_width || '?'}`, icon: 'aspect_ratio' });
    }
    if (pp.size_shortest_edge) {
      items.push({ label: 'Shortest Edge', value: pp.size_shortest_edge, icon: 'straighten' });
    }
    if (pp.size_longest_edge) {
      items.push({ label: 'Longest Edge', value: pp.size_longest_edge, icon: 'straighten' });
    }
    items.push({ label: 'Rescale', value: pp.do_rescale ? 'Yes' : 'No', icon: 'tune' });
    if (pp.rescale_factor != null) {
      items.push({ label: 'Rescale Factor', value: pp.rescale_factor.toFixed(6), icon: 'calculate' });
    }
    items.push({ label: 'Normalize', value: pp.do_normalize ? 'Yes' : 'No', icon: 'equalizer' });
    if (pp.image_mean) {
      items.push({ label: 'Image Mean', value: '[' + pp.image_mean.map(v => v.toFixed(4)).join(', ') + ']', icon: 'analytics' });
    }
    if (pp.image_std) {
      items.push({ label: 'Image Std', value: '[' + pp.image_std.map(v => v.toFixed(4)).join(', ') + ']', icon: 'analytics' });
    }
    items.push({ label: 'Convert RGB', value: pp.do_convert_rgb ? 'Yes' : 'No', icon: 'palette' });
    items.push({ label: 'Center Crop', value: pp.do_center_crop ? 'Yes' : 'No', icon: 'crop' });
    if (pp.crop_size_height || pp.crop_size_width) {
      items.push({ label: 'Crop Size (H x W)', value: `${pp.crop_size_height || '?'} x ${pp.crop_size_width || '?'}`, icon: 'crop_free' });
    }
    items.push({ label: 'Pad', value: pp.do_pad ? 'Yes' : 'No', icon: 'padding' });
    if (pp.pad_size_height || pp.pad_size_width) {
      items.push({ label: 'Pad Size (H x W)', value: `${pp.pad_size_height || '?'} x ${pp.pad_size_width || '?'}`, icon: 'padding' });
    }
    if (pp.patch_size) {
      items.push({ label: 'Patch Size', value: pp.patch_size, icon: 'grid_on' });
    }
    if (pp.num_channels) {
      items.push({ label: 'Channels', value: pp.num_channels, icon: 'layers' });
    }

    return items;
  }

  // ==================== Vision Encoder IO Probe ====================

  isVlmModel(): boolean {
    return this.model.type === 'vlm_pipeline';
  }

  isOcrModel(): boolean {
    const t = this.model.type;
    return t === 'ocr_detection' || t === 'ocr_recognition' || t === 'ocr_table' || t === 'ocr_pipeline';
  }

  probeVisionEncoderIO(): void {
    this.isProbing = true;
    this.stagingService.probeVisionEncoderIO(this.model.model_id).subscribe({
      next: (response: any) => {
        this.isProbing = false;
        if (response.success) {
          // Update local model metadata
          if (!this.model.metadata) {
            this.model.metadata = {} as any;
          }
          this.model.metadata.vision_encoder_pixel_values_name = response.visionEncoderPixelValuesName;
          this.model.metadata.vision_encoder_pixel_attention_mask_name = response.visionEncoderPixelAttentionMaskName;
          this.model.metadata.vision_encoder_primary_output_name = response.visionEncoderPrimaryOutputName;
          this.model.metadata.vision_encoder_output_names = response.visionEncoderOutputNames;
          // Update form
          this.metadataForm.patchValue({
            visionEncoderPixelValuesName: response.visionEncoderPixelValuesName || '',
            visionEncoderPixelAttentionMaskName: response.visionEncoderPixelAttentionMaskName || '',
            visionEncoderPrimaryOutputName: response.visionEncoderPrimaryOutputName || '',
            visionEncoderOutputNames: response.visionEncoderOutputNames?.join(', ') || ''
          });
          this.snackBar.open('Vision encoder IO config probed successfully', 'Close', {
            duration: 3000,
            panelClass: ['snackbar-success']
          });
        } else {
          this.snackBar.open(response.error || 'Probe failed', 'Close', {
            duration: 5000,
            panelClass: ['snackbar-error']
          });
        }
      },
      error: (err) => {
        this.isProbing = false;
        this.snackBar.open('Probe failed: ' + err.message, 'Close', {
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

  // ==================== Optimization Display ====================

  hasOptimizationData(): boolean {
    return !!this.model.metadata?.optimized;
  }

  getOptimizationPasses(): string[] {
    return this.model.metadata?.applied_optimizations || [];
  }

  getOptimizationPreset(): string {
    return this.model.metadata?.optimization_config?.preset || 'N/A';
  }

  getOptimizationReduction(): string {
    const stats = this.model.metadata?.optimization_stats;
    if (stats?.reduction_percent) {
      return stats.reduction_percent.toFixed(1) + '%';
    }
    return 'N/A';
  }

  getOptimizationTime(): string {
    const ms = this.model.metadata?.optimization_time_ms;
    if (!ms) return 'N/A';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
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
    // Pipeline identity
    if (meta.encoderType) {
      items.push({ label: 'Encoder Type', value: meta.encoderType, icon: 'memory' });
    }
    if (meta.ragRole) {
      items.push({ label: 'RAG Role', value: meta.ragRole, icon: 'smart_toy' });
    }
    if (meta.version) {
      items.push({ label: 'Version', value: meta.version, icon: 'tag' });
    }
    // OCR fields
    if (meta.inputHeight || meta.inputWidth) {
      items.push({ label: 'Input Size (H x W)', value: `${meta.inputHeight || '?'} x ${meta.inputWidth || '?'}`, icon: 'aspect_ratio' });
    }
    if (meta.supportedLanguages?.length) {
      items.push({ label: 'Languages', value: meta.supportedLanguages.join(', '), icon: 'translate' });
    }
    if (meta.supportsBatch != null) {
      items.push({ label: 'Batch Support', value: meta.supportsBatch ? 'Yes' : 'No', icon: 'dynamic_feed' });
    }
    if (meta.maxBatchSize) {
      items.push({ label: 'Max Batch Size', value: meta.maxBatchSize, icon: 'stacked_bar_chart' });
    }
    if (meta.supportsHandwriting) {
      items.push({ label: 'Handwriting', value: 'Supported', icon: 'draw' });
    }
    if (meta.averageAccuracy != null) {
      items.push({ label: 'Average Accuracy', value: meta.averageAccuracy.toFixed(1) + '%', icon: 'target' });
    }
    if (meta.ocrVocabSize) {
      items.push({ label: 'OCR Vocab Size', value: meta.ocrVocabSize, icon: 'format_list_numbered' });
    }
    if (meta.usesCtc != null) {
      items.push({ label: 'Uses CTC', value: meta.usesCtc ? 'Yes' : 'No', icon: 'settings_ethernet' });
    }
    // VLM fields
    if (meta.visionFrames) {
      items.push({ label: 'Vision Frames', value: meta.visionFrames, icon: 'burst_mode' });
    }
    if (meta.imageSize) {
      items.push({ label: 'Image Size', value: meta.imageSize + 'px', icon: 'photo_size_select_large' });
    }
    if (meta.tileSize) {
      items.push({ label: 'Tile Size', value: meta.tileSize + 'px', icon: 'grid_on' });
    }
    if (meta.components?.length) {
      items.push({ label: 'Components', value: meta.components.join(', '), icon: 'extension' });
    }
    // Benchmark
    if (meta.benchmark_result) {
      const br = meta.benchmark_result;
      if (br.throughput_tok_per_sec) {
        items.push({ label: 'Throughput', value: br.throughput_tok_per_sec.toFixed(1) + ' tok/s', icon: 'speed' });
      }
      if (br.latency_p99_ms) {
        items.push({ label: 'Latency P99', value: br.latency_p99_ms.toFixed(1) + 'ms', icon: 'timer' });
      }
      if (br.regression != null) {
        items.push({ label: 'Regression', value: br.regression ? 'Yes' : 'No', icon: br.regression ? 'warning' : 'check_circle' });
      }
    }
    // Vision encoder IO config
    if (meta.vision_encoder_pixel_values_name) {
      items.push({ label: 'Vision Pixel Values Input', value: meta.vision_encoder_pixel_values_name, icon: 'input' });
    }
    if (meta.vision_encoder_pixel_attention_mask_name) {
      items.push({ label: 'Vision Attention Mask Input', value: meta.vision_encoder_pixel_attention_mask_name, icon: 'input' });
    }
    if (meta.vision_encoder_primary_output_name) {
      items.push({ label: 'Vision Primary Output', value: meta.vision_encoder_primary_output_name, icon: 'output' });
    }
    if (meta.vision_encoder_output_names?.length) {
      items.push({ label: 'Vision Output Names', value: meta.vision_encoder_output_names.join(', '), icon: 'list' });
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

  // ==================== DSP Execution Plan ====================

  loadExecutionPlan(): void {
    if (this.executionPlanLoaded || this.executionPlanLoading) return;
    if (this.model.status !== 'active' && this.model.status !== 'staged') return;

    this.executionPlanLoading = true;
    this.executionPlanError = null;

    this.compilerService.getGraphInfo(this.model.model_id).subscribe({
      next: (info: GraphInfoResponse) => {
        this.graphInfo = info;
        this.executionPlanStages = this.buildExecutionPlan(info);
        this.executionPlanLoaded = true;
        this.executionPlanLoading = false;
      },
      error: (err) => {
        this.executionPlanLoading = false;
        this.executionPlanError = err.message || 'Failed to load execution plan';
      }
    });
  }

  private buildExecutionPlan(info: GraphInfoResponse): ExecutionPlanStage[] {
    const stages: ExecutionPlanStage[] = [];

    // Stage 1: Input
    stages.push({
      id: 'input',
      label: 'Input',
      type: 'input',
      icon: 'input',
      details: info.inputNames.map(n => ({ label: n, type: 'variable' })),
      stats: { count: info.inputNames.length }
    });

    // Build stages from layer groups if available
    const analysis = info.analysis;
    if (analysis?.layerGroups && analysis.layerGroups.length > 0) {
      for (const group of analysis.layerGroups) {
        stages.push({
          id: 'layer-' + group.name,
          label: group.name,
          type: 'layer_group',
          icon: this.getStageIcon(group.opTypes),
          details: group.opTypes.map(op => ({ label: op, type: 'op' })),
          stats: {
            count: group.count,
            opsPerUnit: group.opsPerGroup
          }
        });
      }
    } else {
      // Fallback: group ops by category from opTypes map
      const categories = this.categorizeOps(info.opTypes);
      for (const cat of categories) {
        stages.push({
          id: 'stage-' + cat.category,
          label: cat.category,
          type: 'op_group',
          icon: cat.icon,
          details: cat.ops.map(o => ({ label: `${o.name} (${o.count})`, type: 'op' })),
          stats: { count: cat.totalOps }
        });
      }
    }

    // Stage: Fusion/Optimization summary (if applicable)
    if (analysis && (analysis.hasAttentionFusion || analysis.hasLinearFusion || analysis.fusedOpCount > 0)) {
      const fusionDetails: { label: string; type: string }[] = [];
      if (analysis.hasAttentionFusion) fusionDetails.push({ label: 'Attention Fusion', type: 'optimization' });
      if (analysis.hasLinearFusion) fusionDetails.push({ label: 'Linear Fusion', type: 'optimization' });
      if (analysis.fusedOpCount > 0) fusionDetails.push({ label: `${analysis.fusedOpCount} fused ops`, type: 'optimization' });

      stages.push({
        id: 'fusion',
        label: 'Fused Operations',
        type: 'optimization',
        icon: 'bolt',
        details: fusionDetails,
        stats: { count: analysis.fusedOpCount }
      });
    }

    // Stage: Output
    stages.push({
      id: 'output',
      label: 'Output',
      type: 'output',
      icon: 'output',
      details: info.outputNames.map(n => ({ label: n, type: 'variable' })),
      stats: { count: info.outputNames.length }
    });

    return stages;
  }

  private categorizeOps(opTypes: { [key: string]: number }): OpCategory[] {
    const categoryMap: { [key: string]: { ops: { name: string; count: number }[]; icon: string } } = {};

    for (const [opName, count] of Object.entries(opTypes)) {
      const cat = this.getOpCategory(opName);
      if (!categoryMap[cat.category]) {
        categoryMap[cat.category] = { ops: [], icon: cat.icon };
      }
      categoryMap[cat.category].ops.push({ name: opName, count });
    }

    // Sort categories in typical execution order
    const order = ['Embedding', 'Attention', 'Normalization', 'Feed-Forward', 'Activation', 'Linear Algebra', 'Reshape/Transform', 'Reduction', 'Other'];
    return order
      .filter(cat => categoryMap[cat])
      .map(cat => ({
        category: cat,
        icon: categoryMap[cat].icon,
        ops: categoryMap[cat].ops.sort((a, b) => b.count - a.count),
        totalOps: categoryMap[cat].ops.reduce((sum, o) => sum + o.count, 0)
      }));
  }

  private getOpCategory(opName: string): { category: string; icon: string } {
    const lower = opName.toLowerCase();
    if (lower.includes('embed')) return { category: 'Embedding', icon: 'text_fields' };
    if (lower.includes('attention') || lower.includes('sdpa')) return { category: 'Attention', icon: 'visibility' };
    if (lower.includes('norm') || lower.includes('layernorm') || lower.includes('batchnorm')) return { category: 'Normalization', icon: 'tune' };
    if (lower.includes('relu') || lower.includes('gelu') || lower.includes('sigmoid') || lower.includes('tanh') || lower.includes('swish') || lower.includes('softmax')) return { category: 'Activation', icon: 'show_chart' };
    if (lower.includes('matmul') || lower.includes('mmul') || lower.includes('linear') || lower.includes('dense') || lower.includes('gemm')) return { category: 'Feed-Forward', icon: 'grid_on' };
    if (lower.includes('add') || lower.includes('sub') || lower.includes('mul') || lower.includes('div')) return { category: 'Linear Algebra', icon: 'calculate' };
    if (lower.includes('reshape') || lower.includes('transpose') || lower.includes('permute') || lower.includes('concat') || lower.includes('slice') || lower.includes('gather')) return { category: 'Reshape/Transform', icon: 'transform' };
    if (lower.includes('reduce') || lower.includes('sum') || lower.includes('mean') || lower.includes('max') || lower.includes('min')) return { category: 'Reduction', icon: 'compress' };
    return { category: 'Other', icon: 'extension' };
  }

  private getStageIcon(opTypes: string[]): string {
    if (opTypes.some(o => o.toLowerCase().includes('attention'))) return 'visibility';
    if (opTypes.some(o => o.toLowerCase().includes('norm'))) return 'tune';
    if (opTypes.some(o => o.toLowerCase().includes('matmul') || o.toLowerCase().includes('mmul'))) return 'grid_on';
    if (opTypes.some(o => o.toLowerCase().includes('embed'))) return 'text_fields';
    return 'layers';
  }

  getStageColor(type: string): string {
    switch (type) {
      case 'input': return '#2196f3';
      case 'output': return '#4caf50';
      case 'layer_group': return '#7c4dff';
      case 'optimization': return '#ff9800';
      case 'op_group': return '#607d8b';
      default: return '#9e9e9e';
    }
  }

  formatBytes(bytes: number): string {
    if (!bytes) return 'N/A';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }

  close(): void {
    this.dialogRef.close({ action: 'closed' });
  }
}

// ==================== Execution Plan Types ====================

export interface ExecutionPlanStage {
  id: string;
  label: string;
  type: 'input' | 'output' | 'layer_group' | 'op_group' | 'optimization';
  icon: string;
  details: { label: string; type: string }[];
  stats: { count: number; opsPerUnit?: number };
}

interface OpCategory {
  category: string;
  icon: string;
  ops: { name: string; count: number }[];
  totalOps: number;
}
