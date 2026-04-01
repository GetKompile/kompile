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
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { MatSnackBar } from '@angular/material/snack-bar';
import { VlmExecutionService, VlmExecutionModel, OcrEngine, ExecutionStatusResponse } from '../../services/vlm-execution.service';
import {
  VlmGenerateRequest,
  VlmGenerateResponse,
  OcrRecognizeResponse,
  OcrTextRegion,
  DocTagsParseResponse,
  TilingPreviewResponse,
  TileInfo
} from '../../models/api-models';

@Component({
  selector: 'app-vlm-execution',
  standalone: false,
  templateUrl: './vlm-execution.component.html',
  styleUrls: ['./vlm-execution.component.css']
})
export class VlmExecutionComponent implements OnInit, OnDestroy {

  // Tab index
  activeTab = 0;

  // ==================== Model State ====================
  availableModels: VlmExecutionModel[] = [];
  executionStatus: ExecutionStatusResponse | null = null;
  selectedModelId = '';
  isLoadingModel = false;

  // ==================== VLM Generation State ====================
  generateImageFile: File | null = null;
  generateImagePreview: string | null = null;
  generatePrompt = 'Describe this image in detail.';
  generateMaxTokens = 512;
  generateTilingEnabled = true;
  generateMaxTiles = 4;
  isGenerating = false;
  generateResult: VlmGenerateResponse | null = null;

  // ==================== OCR State ====================
  ocrImageFile: File | null = null;
  ocrImagePreview: string | null = null;
  ocrEngines: OcrEngine[] = [];
  ocrSelectedEngine = 'deepseek';
  ocrLanguage = 'eng';
  ocrConfidenceThreshold = 0.5;
  isRecognizing = false;
  ocrResult: OcrRecognizeResponse | null = null;

  // ==================== DocTags State ====================
  docTagsRawInput = '';
  isParsing = false;
  docTagsParseResult: DocTagsParseResponse | null = null;
  docTagsMarkdown: string | null = null;
  docTagsHtml: string | null = null;
  isConvertingMarkdown = false;
  isConvertingHtml = false;

  // ==================== Tiling State ====================
  tilingImageFile: File | null = null;
  tilingImagePreview: string | null = null;
  tilingMaxTiles = 4;
  isPreviewing = false;
  tilingResult: TilingPreviewResponse | null = null;

  private destroy$ = new Subject<void>();

  constructor(
    private vlmExecService: VlmExecutionService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadModels();
    this.loadStatus();
    this.loadOcrEngines();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ==================== Model Management ====================

  loadModels(): void {
    this.vlmExecService.getModels()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: models => {
          this.availableModels = models;
        },
        error: err => {
          console.error('Failed to load models:', err);
        }
      });
  }

  loadStatus(): void {
    this.vlmExecService.getStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: status => {
          this.executionStatus = status;
          if (status.activeModelId) {
            this.selectedModelId = status.activeModelId;
          }
        },
        error: err => {
          console.error('Failed to load execution status:', err);
        }
      });
  }

  loadModel(): void {
    if (!this.selectedModelId) {
      this.showSnackbar('Please select a model first', true);
      return;
    }
    this.isLoadingModel = true;
    this.vlmExecService.loadModel(this.selectedModelId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          this.isLoadingModel = false;
          if (response.success) {
            this.showSnackbar('Model loaded successfully');
            this.loadStatus();
          } else {
            this.showSnackbar('Failed to load model: ' + (response.error || 'Unknown error'), true);
          }
        },
        error: err => {
          this.isLoadingModel = false;
          this.showSnackbar('Failed to load model: ' + err.message, true);
        }
      });
  }

  unloadModel(): void {
    this.vlmExecService.unloadModel()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          if (response.success) {
            this.showSnackbar('Model unloaded');
            this.executionStatus = null;
            this.loadStatus();
          } else {
            this.showSnackbar('Failed to unload model: ' + (response.error || 'Unknown error'), true);
          }
        },
        error: err => {
          this.showSnackbar('Failed to unload model: ' + err.message, true);
        }
      });
  }

  isModelLoaded(): boolean {
    return this.executionStatus?.activeModelId != null;
  }

  // ==================== Drag & Drop ====================

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    const target = event.currentTarget as HTMLElement;
    if (target) {
      target.classList.add('drag-over');
    }
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    const target = event.currentTarget as HTMLElement;
    if (target) {
      target.classList.remove('drag-over');
    }
  }

  onGenerateImageDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    const target = event.currentTarget as HTMLElement;
    if (target) { target.classList.remove('drag-over'); }
    if (event.dataTransfer && event.dataTransfer.files.length > 0) {
      const file = event.dataTransfer.files[0];
      if (file.type.startsWith('image/')) {
        this.generateImageFile = file;
        this.generateImagePreview = URL.createObjectURL(file);
      } else {
        this.showSnackbar('Please drop an image file', true);
      }
    }
  }

  onOcrImageDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    const target = event.currentTarget as HTMLElement;
    if (target) { target.classList.remove('drag-over'); }
    if (event.dataTransfer && event.dataTransfer.files.length > 0) {
      const file = event.dataTransfer.files[0];
      if (file.type.startsWith('image/')) {
        this.ocrImageFile = file;
        this.ocrImagePreview = URL.createObjectURL(file);
      } else {
        this.showSnackbar('Please drop an image file', true);
      }
    }
  }

  onTilingImageDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    const target = event.currentTarget as HTMLElement;
    if (target) { target.classList.remove('drag-over'); }
    if (event.dataTransfer && event.dataTransfer.files.length > 0) {
      const file = event.dataTransfer.files[0];
      if (file.type.startsWith('image/')) {
        this.tilingImageFile = file;
        this.tilingImagePreview = URL.createObjectURL(file);
      } else {
        this.showSnackbar('Please drop an image file', true);
      }
    }
  }

  // ==================== VLM Generation ====================

  onGenerateImageSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.generateImageFile = input.files[0];
      this.generateImagePreview = URL.createObjectURL(this.generateImageFile);
    }
  }

  clearGenerateImage(): void {
    this.generateImageFile = null;
    if (this.generateImagePreview) {
      URL.revokeObjectURL(this.generateImagePreview);
      this.generateImagePreview = null;
    }
  }

  runGenerate(): void {
    if (!this.generateImageFile) {
      this.showSnackbar('Please select an image first', true);
      return;
    }

    this.isGenerating = true;
    this.generateResult = null;

    const request: VlmGenerateRequest = {
      prompt: this.generatePrompt,
      maxTokens: this.generateMaxTokens,
      tilingEnabled: this.generateTilingEnabled,
      maxTiles: this.generateMaxTiles,
      modelSetId: this.selectedModelId || undefined
    };

    this.vlmExecService.generate(this.generateImageFile, request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          this.isGenerating = false;
          this.generateResult = response;
          if (!response.success) {
            this.showSnackbar('Generation failed: ' + (response.error || 'Unknown error'), true);
          }
        },
        error: err => {
          this.isGenerating = false;
          this.showSnackbar('Generation failed: ' + err.message, true);
        }
      });
  }

  // ==================== OCR ====================

  loadOcrEngines(): void {
    this.vlmExecService.getOcrEngines()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: engines => {
          this.ocrEngines = engines;
          if (engines.length > 0 && !this.ocrSelectedEngine) {
            this.ocrSelectedEngine = engines[0].type;
          }
        },
        error: err => {
          console.error('Failed to load OCR engines:', err);
        }
      });
  }

  onOcrImageSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.ocrImageFile = input.files[0];
      this.ocrImagePreview = URL.createObjectURL(this.ocrImageFile);
    }
  }

  clearOcrImage(): void {
    this.ocrImageFile = null;
    if (this.ocrImagePreview) {
      URL.revokeObjectURL(this.ocrImagePreview);
      this.ocrImagePreview = null;
    }
  }

  runOcrRecognize(): void {
    if (!this.ocrImageFile) {
      this.showSnackbar('Please select an image first', true);
      return;
    }

    // Update OCR config before recognizing
    this.vlmExecService.updateOcrConfig({
      engineType: this.ocrSelectedEngine,
      language: this.ocrLanguage,
      confidenceThreshold: this.ocrConfidenceThreshold
    }).pipe(takeUntil(this.destroy$)).subscribe();

    this.isRecognizing = true;
    this.ocrResult = null;

    this.vlmExecService.ocrRecognize(this.ocrImageFile)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          this.isRecognizing = false;
          this.ocrResult = response;
          if (!response.success) {
            this.showSnackbar('OCR failed: ' + (response.error || 'Unknown error'), true);
          }
        },
        error: err => {
          this.isRecognizing = false;
          this.showSnackbar('OCR failed: ' + err.message, true);
        }
      });
  }

  getConfidenceColor(confidence: number): string {
    if (confidence >= 0.9) return '#4caf50';
    if (confidence >= 0.7) return '#ff9800';
    return '#f44336';
  }

  // ==================== DocTags ====================

  runParseDocTags(): void {
    if (!this.docTagsRawInput.trim()) {
      this.showSnackbar('Please enter DocTags content', true);
      return;
    }

    this.isParsing = true;
    this.docTagsParseResult = null;
    this.docTagsMarkdown = null;
    this.docTagsHtml = null;

    this.vlmExecService.parseDocTags(this.docTagsRawInput)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          this.isParsing = false;
          this.docTagsParseResult = response;
          if (!response.success) {
            this.showSnackbar('Parse failed: ' + (response.error || 'Unknown error'), true);
          }
        },
        error: err => {
          this.isParsing = false;
          this.showSnackbar('Parse failed: ' + err.message, true);
        }
      });
  }

  convertToMarkdown(): void {
    if (!this.docTagsRawInput.trim()) {
      this.showSnackbar('Please enter DocTags content', true);
      return;
    }

    this.isConvertingMarkdown = true;
    this.docTagsMarkdown = null;

    this.vlmExecService.docTagsToMarkdown(this.docTagsRawInput)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          this.isConvertingMarkdown = false;
          if (response.success) {
            this.docTagsMarkdown = response.markdown || '';
          } else {
            this.showSnackbar('Markdown conversion failed', true);
          }
        },
        error: err => {
          this.isConvertingMarkdown = false;
          this.showSnackbar('Markdown conversion failed: ' + err.message, true);
        }
      });
  }

  convertToHtml(): void {
    if (!this.docTagsRawInput.trim()) {
      this.showSnackbar('Please enter DocTags content', true);
      return;
    }

    this.isConvertingHtml = true;
    this.docTagsHtml = null;

    this.vlmExecService.docTagsToHtml(this.docTagsRawInput)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          this.isConvertingHtml = false;
          if (response.success) {
            this.docTagsHtml = response.html || '';
          } else {
            this.showSnackbar('HTML conversion failed', true);
          }
        },
        error: err => {
          this.isConvertingHtml = false;
          this.showSnackbar('HTML conversion failed: ' + err.message, true);
        }
      });
  }

  // ==================== Tiling ====================

  onTilingImageSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.tilingImageFile = input.files[0];
      this.tilingImagePreview = URL.createObjectURL(this.tilingImageFile);
    }
  }

  clearTilingImage(): void {
    this.tilingImageFile = null;
    if (this.tilingImagePreview) {
      URL.revokeObjectURL(this.tilingImagePreview);
      this.tilingImagePreview = null;
    }
  }

  runTilingPreview(): void {
    if (!this.tilingImageFile) {
      this.showSnackbar('Please select an image first', true);
      return;
    }

    this.isPreviewing = true;
    this.tilingResult = null;

    this.vlmExecService.tilingPreview(this.tilingImageFile, this.tilingMaxTiles)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          this.isPreviewing = false;
          this.tilingResult = response;
          if (!response.success) {
            this.showSnackbar('Tiling preview failed: ' + (response.error || 'Unknown error'), true);
          }
        },
        error: err => {
          this.isPreviewing = false;
          this.showSnackbar('Tiling preview failed: ' + err.message, true);
        }
      });
  }

  getTileStyle(tile: TileInfo): { [key: string]: string } {
    if (!this.tilingResult || !this.tilingResult.originalWidth || !this.tilingResult.originalHeight) {
      return {};
    }
    const scaleX = 100 / this.tilingResult.originalWidth;
    const scaleY = 100 / this.tilingResult.originalHeight;
    return {
      'left': (tile.x * scaleX) + '%',
      'top': (tile.y * scaleY) + '%',
      'width': (tile.width * scaleX) + '%',
      'height': (tile.height * scaleY) + '%'
    };
  }

  // ==================== Helpers ====================

  refreshAll(): void {
    this.loadModels();
    this.loadStatus();
    this.loadOcrEngines();
    this.showSnackbar('Data refreshed');
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
