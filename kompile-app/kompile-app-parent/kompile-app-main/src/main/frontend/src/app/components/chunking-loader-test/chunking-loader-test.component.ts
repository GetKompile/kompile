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

import { Component, OnInit, OnDestroy, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, FormControl, FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSliderModule } from '@angular/material/slider';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTabsModule } from '@angular/material/tabs';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { TextFieldModule } from '@angular/cdk/text-field';
import { HttpClient } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { trigger, transition, style, animate } from '@angular/animations';

import {
  LoaderInfo,
  ChunkerInfo,
  ChunkerStrategy,
  ChunkerStrategyInfo,
  ChunkingPreset,
  ChunkingConfig,
  ChunkingOptions,
  CHUNKER_STRATEGIES,
  CHUNKING_PRESETS,
  DEFAULT_CHUNKING_OPTIONS,
  TokenizerOption,
  AVAILABLE_TOKENIZERS
} from '../../models/api-models';
import { DocumentService } from '../../services/document.service';
import { OcrService } from '../../services/ocr.service';
import { OcrStatus, OcrConfig, OcrModelInfo } from '../../models/ocr-models';
import { backendUrl } from '../../services/base.service';

// Debug analysis result types matching backend
interface LoaderDebugInfo {
  name: string;
  className: string;
  isNoOp: boolean;
  supportsFile: boolean;
  supportReason: string;
}

interface ChunkerDebugInfo {
  name: string;
  className: string;
  isNoOp: boolean;
  reason: string;
}

interface ContentStats {
  isNull: boolean;
  length?: number;
  isEmpty?: boolean;
  lineCount?: number;
  wordCount?: number;
  hasSpecialChars?: boolean;
  hasEncodingIssues?: boolean;
  containsCommonWords?: boolean;
}

interface DocumentDebugInfo {
  id: string;
  text: string;
  contentLength: number;
  hasContent: boolean;
  metadata: { [key: string]: any };
  contentPreview?: string;
  contentStats: ContentStats;
}

interface ChunkDebugInfo {
  id: string;
  text: string;
  contentLength: number;
  chunkIndex: number;
  metadata: { [key: string]: any };
  score?: number;
}

interface LoaderComparisonStats {
  loaderName: string;
  loaderClassName: string;
  documentCount: number;
  totalCharacters: number;
  totalWords: number;
  hadError: boolean;
  errorMessage?: string;
  processingTimeMs: number;
}

interface CompositeLoaderComparison {
  compositeLoaderUsed: boolean;
  selectedLoader: string;
  selectionReason: string;
  loadersCompared: number;
  loaderStats: { [loaderName: string]: LoaderComparisonStats };
}

interface DebugAnalysisResult {
  fileName: string;
  filePath: string;
  fileSize: number;
  availableLoaders: LoaderDebugInfo[];
  selectedLoader: LoaderDebugInfo | null;
  loadedDocuments: DocumentDebugInfo[];
  availableChunkers: ChunkerDebugInfo[];
  selectedChunker: ChunkerDebugInfo | null;
  chunks: ChunkDebugInfo[];
  processingStats: { [key: string]: any };
  errorMessage?: string;
  compositeLoaderComparison?: CompositeLoaderComparison;
}

interface TestUploadResponse {
  message: string;
  fileName: string;
  filePath: string;
  fileSize: number;
}

type SourceType = 'file' | 'url' | 'path' | 'text';

@Component({
  selector: 'app-chunking-loader-test',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    FormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatCheckboxModule,
    MatSliderModule,
    MatSlideToggleModule,
    MatChipsModule,
    MatTooltipModule,
    MatDividerModule,
    MatExpansionModule,
    MatTabsModule,
    MatCardModule,
    MatTableModule,
    MatPaginatorModule,
    MatSnackBarModule,
    TextFieldModule
  ],
  templateUrl: './chunking-loader-test.component.html',
  styleUrls: ['./chunking-loader-test.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [
    trigger('expandCollapse', [
      transition(':enter', [
        style({ height: '0', opacity: 0, overflow: 'hidden' }),
        animate('200ms ease-out', style({ height: '*', opacity: 1 }))
      ]),
      transition(':leave', [
        style({ height: '*', opacity: 1, overflow: 'hidden' }),
        animate('200ms ease-in', style({ height: '0', opacity: 0 }))
      ])
    ]),
    trigger('fadeIn', [
      transition(':enter', [
        style({ opacity: 0 }),
        animate('300ms ease-out', style({ opacity: 1 }))
      ])
    ])
  ]
})
export class ChunkingLoaderTestComponent implements OnInit, OnDestroy {
  // Source selection
  sourceType: SourceType = 'file';
  selectedFile: File | null = null;
  selectedFiles: File[] = [];
  urlInput: string = '';
  pathInput: string = '';
  textInput: string = '';
  textSourceName: string = '';
  isDragOver: boolean = false;
  fileErrorMessage: string | null = null;

  // Loader/Chunker selection
  availableLoaders: LoaderInfo[] = [];
  availableChunkers: ChunkerInfo[] = [];
  selectedLoader: string = '';
  selectedChunker: string = '';

  // Composite PDF loader option
  useCompositePdfLoader: boolean = false;
  showCompositeLoaderComparison: boolean = true;

  // Chunking configuration
  showChunkingOptions: boolean = true;
  showAdvancedOptions: boolean = false;
  selectedPreset: string = 'default';
  chunkerStrategies: ChunkerStrategyInfo[] = CHUNKER_STRATEGIES;
  chunkingPresets: ChunkingPreset[] = CHUNKING_PRESETS;
  chunkingConfig: ChunkingConfig = {
    useCustomSettings: false,
    strategy: 'auto',
    options: { ...DEFAULT_CHUNKING_OPTIONS }
  };

  // Tokenizer configuration
  showTokenizerOptions: boolean = false;
  availableTokenizers: TokenizerOption[] = AVAILABLE_TOKENIZERS;
  selectedTokenizer: string = 'default';
  enablePreTokenization: boolean = false;
  maxTokenLength: number = 512;

  // Processing state
  isUploading: boolean = false;
  isAnalyzing: boolean = false;
  uploadProgress: number = 0;
  analysisProgress: number = 0;

  // Results
  analysisResult: DebugAnalysisResult | null = null;
  showLoadedDocuments: boolean = true;
  showChunks: boolean = true;
  selectedDocumentIndex: number = 0;
  selectedChunkIndex: number | null = null;

  // Chunk pagination and display
  chunkPageSize: number = 10;
  chunkPageIndex: number = 0;
  displayedChunks: ChunkDebugInfo[] = [];
  chunkViewMode: 'cards' | 'list' = 'cards';
  expandedChunkIndex: number | null = null;

  // Math reference for template
  Math = Math;

  // Compare mode - show before/after
  compareMode: boolean = false;

  // Clipboard state
  isPasting: boolean = false;
  clipboardError: string | null = null;

  // OCR status
  ocrStatus: OcrStatus | null = null;
  ocrConfig: OcrConfig | null = null;
  ocrModels: OcrModelInfo[] = [];
  isLoadingOcrStatus: boolean = false;
  ocrStatusError: string | null = null;
  showOcrStatusDetails: boolean = false;

  // File types that typically require OCR
  private readonly ocrRequiredExtensions: Set<string> = new Set([
    'png', 'jpg', 'jpeg', 'gif', 'bmp', 'tiff', 'tif', 'webp',
    'heic', 'heif', 'raw', 'svg'
  ]);

  // File types that may benefit from OCR (scanned PDFs)
  private readonly ocrBeneficialExtensions: Set<string> = new Set([
    'pdf'
  ]);

  private subscriptions: Subscription = new Subscription();

  constructor(
    private documentService: DocumentService,
    private ocrService: OcrService,
    private http: HttpClient,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadAvailableLoadersAndChunkers();
    this.loadOcrStatus();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  private loadAvailableLoadersAndChunkers(): void {
    this.subscriptions.add(
      this.documentService.getAvailableLoaders().subscribe({
        next: (loaders) => {
          this.availableLoaders = loaders;
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error('Failed to load loaders:', err);
          this.snackBar.open('Failed to load available loaders', 'Dismiss', { duration: 3000 });
        }
      })
    );

    this.subscriptions.add(
      this.documentService.getAvailableChunkers().subscribe({
        next: (chunkers) => {
          this.availableChunkers = chunkers;
          this.mergeBackendChunkers();
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error('Failed to load chunkers:', err);
          this.snackBar.open('Failed to load available chunkers', 'Dismiss', { duration: 3000 });
        }
      })
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // OCR STATUS METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  loadOcrStatus(): void {
    this.isLoadingOcrStatus = true;
    this.ocrStatusError = null;
    this.cdr.markForCheck();

    // Load status, config, and models in parallel
    this.subscriptions.add(
      this.ocrService.getStatus().subscribe({
        next: (status) => {
          this.ocrStatus = status;
          this.isLoadingOcrStatus = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error('Failed to load OCR status:', err);
          this.ocrStatusError = this.getOcrErrorMessage(err);
          this.ocrStatus = {
            ocrEnabled: false,
            pipelineReady: false,
            postProcessorAvailable: false
          };
          this.isLoadingOcrStatus = false;
          this.cdr.markForCheck();
        }
      })
    );

    this.subscriptions.add(
      this.ocrService.getConfig().subscribe({
        next: (config) => {
          this.ocrConfig = config;
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error('Failed to load OCR config:', err);
        }
      })
    );

    this.subscriptions.add(
      this.ocrService.getModels().subscribe({
        next: (models) => {
          this.ocrModels = models;
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error('Failed to load OCR models:', err);
        }
      })
    );
  }

  refreshOcrStatus(): void {
    this.loadOcrStatus();
  }

  toggleOcrStatusDetails(): void {
    this.showOcrStatusDetails = !this.showOcrStatusDetails;
    this.cdr.markForCheck();
  }

  private getOcrErrorMessage(error: any): string {
    return error?.error?.message || error?.error?.error || error?.message || 'OCR service unavailable';
  }

  /**
   * Check if any selected file requires OCR processing.
   */
  hasFilesRequiringOcr(): boolean {
    return this.selectedFiles.some(file => this.fileRequiresOcr(file.name));
  }

  /**
   * Check if any selected file may benefit from OCR (e.g., PDFs that could be scanned).
   */
  hasFilesThatMayBenefitFromOcr(): boolean {
    return this.selectedFiles.some(file => this.fileMayBenefitFromOcr(file.name));
  }

  /**
   * Check if a file definitely requires OCR (image files).
   */
  fileRequiresOcr(fileName: string): boolean {
    const ext = this.getFileExtension(fileName);
    return this.ocrRequiredExtensions.has(ext);
  }

  /**
   * Check if a file may benefit from OCR (e.g., PDFs).
   */
  fileMayBenefitFromOcr(fileName: string): boolean {
    const ext = this.getFileExtension(fileName);
    return this.ocrBeneficialExtensions.has(ext);
  }

  private getFileExtension(fileName: string): string {
    return fileName.split('.').pop()?.toLowerCase() || '';
  }

  /**
   * Check if OCR is fully available (enabled and pipeline ready).
   */
  isOcrAvailable(): boolean {
    return !!(this.ocrStatus?.ocrEnabled && this.ocrStatus?.pipelineReady);
  }

  /**
   * Get the count of loaded OCR models.
   */
  getLoadedOcrModelCount(): number {
    return this.ocrModels.filter(m => m.isLoaded).length;
  }

  /**
   * Get OCR status text for display.
   */
  getOcrStatusText(): string {
    if (this.isLoadingOcrStatus) {
      return 'Checking OCR status...';
    }
    if (this.ocrStatusError) {
      return `OCR Error: ${this.ocrStatusError}`;
    }
    if (!this.ocrStatus) {
      return 'OCR status unknown';
    }
    if (!this.ocrStatus.ocrEnabled) {
      return 'OCR is disabled';
    }
    if (!this.ocrStatus.pipelineReady) {
      return 'OCR pipeline not ready - models may need to be loaded';
    }
    return 'OCR is available';
  }

  /**
   * Get OCR status color for the indicator.
   */
  getOcrStatusColor(): string {
    if (this.isLoadingOcrStatus) return 'accent';
    if (this.ocrStatusError || !this.ocrStatus?.ocrEnabled) return 'warn';
    if (!this.ocrStatus?.pipelineReady) return 'warn';
    return 'primary';
  }

  /**
   * Get OCR status icon.
   */
  getOcrStatusIcon(): string {
    if (this.isLoadingOcrStatus) return 'hourglass_empty';
    if (this.ocrStatusError) return 'error';
    if (!this.ocrStatus?.ocrEnabled) return 'visibility_off';
    if (!this.ocrStatus?.pipelineReady) return 'warning';
    return 'check_circle';
  }

  /**
   * Check if we should show an OCR warning for the current file selection.
   */
  shouldShowOcrWarning(): boolean {
    if (!this.selectedFiles.length) return false;
    if (this.isOcrAvailable()) return false;
    return this.hasFilesRequiringOcr() || this.hasFilesThatMayBenefitFromOcr();
  }

  /**
   * Get the OCR warning message for display.
   */
  getOcrWarningMessage(): string {
    const requiresOcr = this.hasFilesRequiringOcr();
    const mayBenefit = this.hasFilesThatMayBenefitFromOcr();

    if (requiresOcr) {
      const imageFiles = this.selectedFiles.filter(f => this.fileRequiresOcr(f.name)).map(f => f.name);
      return `Image files (${imageFiles.join(', ')}) require OCR for text extraction. ` +
        'OCR is currently ' + (this.ocrStatus?.ocrEnabled ? 'enabled but the pipeline is not ready' : 'not available') + '.';
    }

    if (mayBenefit) {
      return 'PDF files may contain scanned images that require OCR for proper text extraction. ' +
        'OCR is currently ' + (this.ocrStatus?.ocrEnabled ? 'enabled but the pipeline is not ready' : 'not available') + '.';
    }

    return '';
  }

  /**
   * Merges backend chunkers with the static CHUNKER_STRATEGIES list.
   */
  private mergeBackendChunkers(): void {
    if (!this.availableChunkers || this.availableChunkers.length === 0) {
      return;
    }

    const existingIds = new Set(this.chunkerStrategies.map(s => s.id));

    for (const backendChunker of this.availableChunkers) {
      if (backendChunker.name.toLowerCase().includes('noop') ||
        backendChunker.name.toLowerCase().includes('no-op')) {
        continue;
      }

      if (!existingIds.has(backendChunker.name as ChunkerStrategy)) {
        const newStrategy: ChunkerStrategyInfo = {
          id: backendChunker.name as ChunkerStrategy,
          name: this.formatChunkerName(backendChunker.name),
          description: `Backend chunker: ${backendChunker.className}`,
          icon: this.getIconForChunker(backendChunker.name),
          bestFor: 'Custom chunking'
        };
        this.chunkerStrategies.push(newStrategy);
        existingIds.add(backendChunker.name as ChunkerStrategy);
      }
    }
  }

  private formatChunkerName(name: string): string {
    return name
      .replace(/_/g, ' ')
      .replace(/-/g, ' ')
      .split(' ')
      .map(word => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ');
  }

  private getIconForChunker(name: string): string {
    const lowerName = name.toLowerCase();
    if (lowerName.includes('sentence')) return 'short_text';
    if (lowerName.includes('token')) return 'tag';
    if (lowerName.includes('markdown')) return 'code';
    if (lowerName.includes('recursive') || lowerName.includes('character')) return 'account_tree';
    return 'content_cut';
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SOURCE SELECTION METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  selectSourceType(type: SourceType): void {
    this.sourceType = type;
    this.clearResults();
    this.cdr.markForCheck();
  }

  onFileSelectedChange(event: Event): void {
    const element = event.target as HTMLInputElement;
    this.fileErrorMessage = null;
    if (element.files && element.files.length > 0) {
      this.selectedFiles = Array.from(element.files);
      this.selectedFile = this.selectedFiles[0];
      this.clearResults();
    } else {
      this.selectedFile = null;
      this.selectedFiles = [];
    }
    this.cdr.markForCheck();
  }

  triggerFileInput(): void {
    if (this.isUploading || this.isAnalyzing) return;
    const fileInput = document.getElementById('testFileInput') as HTMLInputElement;
    if (fileInput) {
      fileInput.click();
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    if (!this.isUploading && !this.isAnalyzing) {
      this.isDragOver = true;
      this.cdr.markForCheck();
    }
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;
    this.cdr.markForCheck();
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;

    if (this.isUploading || this.isAnalyzing) return;

    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      const newFiles = Array.from(files);
      this.selectedFiles = [...this.selectedFiles, ...newFiles];
      this.selectedFile = this.selectedFiles[0];
      this.fileErrorMessage = null;
      this.clearResults();
      this.cdr.markForCheck();
    }
  }

  removeFile(index: number): void {
    this.selectedFiles.splice(index, 1);
    this.selectedFile = this.selectedFiles.length > 0 ? this.selectedFiles[0] : null;
    this.clearResults();
    this.cdr.markForCheck();
  }

  getFileIcon(fileName: string): string {
    const ext = fileName.split('.').pop()?.toLowerCase() || '';
    const iconMap: { [key: string]: string } = {
      'pdf': 'picture_as_pdf',
      'doc': 'description',
      'docx': 'description',
      'txt': 'article',
      'html': 'code',
      'htm': 'code',
      'xml': 'code',
      'json': 'data_object',
      'csv': 'table_chart',
      'xls': 'table_chart',
      'xlsx': 'table_chart',
      'md': 'article',
      'eml': 'email',
      'msg': 'email'
    };
    return iconMap[ext] || 'insert_drive_file';
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  getTotalFileSize(): string {
    const totalBytes = this.selectedFiles.reduce((sum, file) => sum + file.size, 0);
    return this.formatFileSize(totalBytes);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CLIPBOARD METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  async pasteFromClipboard(): Promise<void> {
    if (this.isUploading || this.isAnalyzing || this.isPasting) return;

    this.isPasting = true;
    this.clipboardError = null;
    this.cdr.markForCheck();

    try {
      if (!navigator.clipboard || !navigator.clipboard.readText) {
        this.clipboardError = 'Clipboard access not supported in this browser.';
        return;
      }

      const text = await navigator.clipboard.readText();

      if (text && text.trim()) {
        this.textInput = text;
        this.clipboardError = null;
        this.clearResults();
      } else {
        this.clipboardError = 'Clipboard is empty or contains no text.';
      }
    } catch (err: any) {
      console.error('Failed to read clipboard:', err);
      if (err.name === 'NotAllowedError') {
        this.clipboardError = 'Permission denied. Please allow clipboard access.';
      } else {
        this.clipboardError = 'Could not read clipboard. Please paste manually.';
      }
    } finally {
      this.isPasting = false;
      this.cdr.markForCheck();
    }
  }

  clearTextInput(): void {
    this.textInput = '';
    this.clipboardError = null;
    this.clearResults();
    this.cdr.markForCheck();
  }

  getTextCharacterCount(): number {
    return this.textInput?.length || 0;
  }

  getTextWordCount(): number {
    if (!this.textInput || !this.textInput.trim()) return 0;
    return this.textInput.trim().split(/\s+/).filter(word => word.length > 0).length;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CHUNKING CONFIGURATION METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  toggleChunkingSection(): void {
    this.showChunkingOptions = !this.showChunkingOptions;
    this.cdr.markForCheck();
  }

  toggleAdvancedOptions(): void {
    this.showAdvancedOptions = !this.showAdvancedOptions;
    this.cdr.markForCheck();
  }

  toggleTokenizerSection(): void {
    this.showTokenizerOptions = !this.showTokenizerOptions;
    this.cdr.markForCheck();
  }

  applyPreset(presetId: string): void {
    const preset = this.chunkingPresets.find(p => p.id === presetId);
    if (preset && preset.config) {
      this.selectedPreset = presetId;
      this.chunkingConfig.useCustomSettings = presetId !== 'default';

      if (preset.config.strategy) {
        this.chunkingConfig.strategy = preset.config.strategy;
      }

      if (preset.config.options) {
        this.chunkingConfig.options = {
          ...DEFAULT_CHUNKING_OPTIONS,
          ...preset.config.options
        };
      }

      this.cdr.markForCheck();
    }
  }

  selectStrategy(strategyId: ChunkerStrategy): void {
    if (this.isUploading || this.isAnalyzing) return;

    this.chunkingConfig.strategy = strategyId;
    this.chunkingConfig.useCustomSettings = strategyId !== 'auto';
    this.selectedPreset = 'custom';
    this.cdr.markForCheck();
  }

  onChunkingOptionChange(): void {
    this.chunkingConfig.useCustomSettings = true;
    this.selectedPreset = 'custom';
    this.cdr.markForCheck();
  }

  getActivePresetName(): string {
    if (this.selectedPreset === 'custom') return 'Custom';
    const preset = this.chunkingPresets.find(p => p.id === this.selectedPreset);
    return preset?.name || 'Default';
  }

  onTokenizerChange(tokenizerId: string): void {
    this.selectedTokenizer = tokenizerId;
    const tokenizer = this.availableTokenizers.find(t => t.id === tokenizerId);
    if (tokenizer) {
      this.maxTokenLength = tokenizer.maxLength;
    }
    this.cdr.markForCheck();
  }

  getSelectedTokenizerName(): string {
    const tokenizer = this.availableTokenizers.find(t => t.id === this.selectedTokenizer);
    return tokenizer?.name || 'Default';
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ANALYSIS METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  canRunTest(): boolean {
    if (this.isUploading || this.isAnalyzing) return false;

    switch (this.sourceType) {
      case 'file':
        return this.selectedFiles.length > 0;
      case 'url':
        return !!this.urlInput && this.urlInput.trim().length > 0;
      case 'path':
        return !!this.pathInput && this.pathInput.trim().length > 0;
      case 'text':
        return !!this.textInput && this.textInput.trim().length > 0;
      default:
        return false;
    }
  }

  async runTest(): Promise<void> {
    if (!this.canRunTest()) return;

    this.clearResults();

    switch (this.sourceType) {
      case 'file':
        await this.testFileUpload();
        break;
      case 'url':
        this.snackBar.open('URL testing not yet implemented. Please use file upload.', 'Dismiss', { duration: 3000 });
        break;
      case 'path':
        await this.testServerPath();
        break;
      case 'text':
        await this.testTextContent();
        break;
    }
  }

  private async testFileUpload(): Promise<void> {
    if (!this.selectedFile) return;

    this.isUploading = true;
    this.uploadProgress = 0;
    this.cdr.markForCheck();

    try {
      // Step 1: Upload file for testing
      this.uploadProgress = 20;
      this.cdr.markForCheck();

      const formData = new FormData();
      formData.append('file', this.selectedFile, this.selectedFile.name);

      const uploadResponse = await this.http.post<TestUploadResponse>(
        `${backendUrl}/documents/debug/test-upload`,
        formData
      ).toPromise();

      if (!uploadResponse) {
        throw new Error('Upload failed - no response');
      }

      this.uploadProgress = 50;
      this.cdr.markForCheck();

      // Step 2: Analyze the uploaded file
      await this.analyzeFile(uploadResponse.fileName);

    } catch (err: any) {
      console.error('Test upload failed:', err);
      this.snackBar.open(`Upload failed: ${err.message || 'Unknown error'}`, 'Dismiss', { duration: 5000 });
    } finally {
      this.isUploading = false;
      this.uploadProgress = 0;
      this.cdr.markForCheck();
    }
  }

  private async testServerPath(): Promise<void> {
    // For server path, we directly analyze without uploading
    const fileName = this.pathInput.split('/').pop() || this.pathInput;
    await this.analyzeFile(fileName);
  }

  private async testTextContent(): Promise<void> {
    // For text content, we create a temporary file and upload it
    this.isUploading = true;
    this.uploadProgress = 0;
    this.cdr.markForCheck();

    try {
      const blob = new Blob([this.textInput], { type: 'text/plain' });
      const fileName = (this.textSourceName || 'pasted_text') + '.txt';
      const file = new File([blob], fileName, { type: 'text/plain' });

      this.uploadProgress = 20;
      this.cdr.markForCheck();

      const formData = new FormData();
      formData.append('file', file, file.name);

      const uploadResponse = await this.http.post<TestUploadResponse>(
        `${backendUrl}/documents/debug/test-upload`,
        formData
      ).toPromise();

      if (!uploadResponse) {
        throw new Error('Upload failed - no response');
      }

      this.uploadProgress = 50;
      this.cdr.markForCheck();

      await this.analyzeFile(uploadResponse.fileName);

    } catch (err: any) {
      console.error('Text test failed:', err);
      this.snackBar.open(`Text test failed: ${err.message || 'Unknown error'}`, 'Dismiss', { duration: 5000 });
    } finally {
      this.isUploading = false;
      this.uploadProgress = 0;
      this.cdr.markForCheck();
    }
  }

  private async analyzeFile(fileName: string): Promise<void> {
    this.isAnalyzing = true;
    this.analysisProgress = 0;
    this.cdr.markForCheck();

    try {
      this.analysisProgress = 30;
      this.cdr.markForCheck();

      // Build query params
      let url = `${backendUrl}/documents/debug/analyze-file?fileName=${encodeURIComponent(fileName)}`;

      if (this.selectedLoader) {
        url += `&loaderName=${encodeURIComponent(this.selectedLoader)}`;
      }

      // Use the strategy as the chunker name
      const chunkerName = this.chunkingConfig.strategy !== 'auto' ? this.chunkingConfig.strategy : this.selectedChunker;
      if (chunkerName) {
        url += `&chunkerName=${encodeURIComponent(chunkerName)}`;
      }

      // Add chunking options
      if (this.chunkingConfig.options.chunkSize) {
        url += `&chunkSize=${this.chunkingConfig.options.chunkSize}`;
      }
      if (this.chunkingConfig.options.overlap !== undefined) {
        url += `&overlap=${this.chunkingConfig.options.overlap}`;
      }

      // Add composite PDF loader option (only for PDFs without a specific loader selected)
      if (this.useCompositePdfLoader && fileName.toLowerCase().endsWith('.pdf') && !this.selectedLoader) {
        url += `&useCompositePdfLoader=true`;
      }

      this.analysisProgress = 50;
      this.cdr.markForCheck();

      const result = await this.http.post<DebugAnalysisResult>(url, null).toPromise();

      this.analysisProgress = 90;
      this.cdr.markForCheck();

      if (result) {
        this.analysisResult = result;
        this.updateDisplayedChunks();

        if (result.errorMessage) {
          this.snackBar.open(`Analysis warning: ${result.errorMessage}`, 'Dismiss', { duration: 5000 });
        } else {
          this.snackBar.open(
            `Analysis complete: ${result.loadedDocuments?.length || 0} documents, ${result.chunks?.length || 0} chunks`,
            'Dismiss',
            { duration: 3000 }
          );
        }
      }

    } catch (err: any) {
      console.error('Analysis failed:', err);
      this.snackBar.open(`Analysis failed: ${err.message || 'Unknown error'}`, 'Dismiss', { duration: 5000 });
    } finally {
      this.isAnalyzing = false;
      this.analysisProgress = 100;
      this.cdr.markForCheck();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // RESULTS DISPLAY METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  clearResults(): void {
    this.analysisResult = null;
    this.selectedDocumentIndex = 0;
    this.selectedChunkIndex = null;
    this.chunkPageIndex = 0;
    this.displayedChunks = [];
    this.cdr.markForCheck();
  }

  selectDocument(index: number): void {
    this.selectedDocumentIndex = index;
    this.cdr.markForCheck();
  }

  selectChunk(index: number): void {
    this.selectedChunkIndex = this.selectedChunkIndex === index ? null : index;
    this.cdr.markForCheck();
  }

  onChunkPageChange(event: PageEvent): void {
    this.chunkPageSize = event.pageSize;
    this.chunkPageIndex = event.pageIndex;
    this.updateDisplayedChunks();
  }

  updateDisplayedChunks(): void {
    if (!this.analysisResult?.chunks) {
      this.displayedChunks = [];
      return;
    }

    const startIndex = this.chunkPageIndex * this.chunkPageSize;
    const endIndex = startIndex + this.chunkPageSize;
    this.displayedChunks = this.analysisResult.chunks.slice(startIndex, endIndex);
    this.cdr.markForCheck();
  }

  getSelectedDocument(): DocumentDebugInfo | null {
    if (!this.analysisResult?.loadedDocuments || this.selectedDocumentIndex < 0) {
      return null;
    }
    return this.analysisResult.loadedDocuments[this.selectedDocumentIndex] || null;
  }

  getDocumentPreview(doc: DocumentDebugInfo): string {
    if (!doc.text) return '(No content)';
    return doc.text.length > 300 ? doc.text.substring(0, 300) + '...' : doc.text;
  }

  getChunkPreview(chunk: ChunkDebugInfo): string {
    if (!chunk.text) return '(No content)';
    return chunk.text.length > 200 ? chunk.text.substring(0, 200) + '...' : chunk.text;
  }

  getLoaderClass(loader: LoaderDebugInfo): string {
    if (loader.isNoOp) return 'loader-noop';
    if (loader.supportsFile) return 'loader-supports';
    return 'loader-unsupported';
  }

  getChunkerClass(chunker: ChunkerDebugInfo): string {
    if (chunker.isNoOp) return 'chunker-noop';
    return 'chunker-active';
  }

  toggleChunkViewMode(): void {
    this.chunkViewMode = this.chunkViewMode === 'cards' ? 'list' : 'cards';
    this.cdr.markForCheck();
  }

  toggleChunkExpanded(index: number): void {
    this.expandedChunkIndex = this.expandedChunkIndex === index ? null : index;
    this.cdr.markForCheck();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // UTILITY METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  copyToClipboard(text: string): void {
    navigator.clipboard.writeText(text).then(() => {
      this.snackBar.open('Copied to clipboard', 'Dismiss', { duration: 2000 });
    }).catch(err => {
      console.error('Failed to copy:', err);
    });
  }

  downloadResults(): void {
    if (!this.analysisResult) return;

    const blob = new Blob([JSON.stringify(this.analysisResult, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `chunk-analysis-${this.analysisResult.fileName || 'result'}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }

  getMetadataKeys(metadata: { [key: string]: any }): string[] {
    return Object.keys(metadata || {});
  }

  formatMetadataValue(value: any): string {
    if (value === null || value === undefined) return 'null';
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // COMPOSITE PDF LOADER METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Check if any selected file is a PDF.
   */
  hasSelectedPdfFiles(): boolean {
    return this.selectedFiles.some(file => file.name.toLowerCase().endsWith('.pdf'));
  }

  /**
   * Toggle the composite PDF loader option.
   */
  toggleCompositePdfLoader(): void {
    this.useCompositePdfLoader = !this.useCompositePdfLoader;
    this.cdr.markForCheck();
  }

  /**
   * Toggle the display of composite loader comparison details.
   */
  toggleCompositeLoaderComparison(): void {
    this.showCompositeLoaderComparison = !this.showCompositeLoaderComparison;
    this.cdr.markForCheck();
  }

  /**
   * Check if the composite loader comparison should be shown.
   */
  hasCompositeLoaderComparison(): boolean {
    return !!(this.analysisResult?.compositeLoaderComparison?.compositeLoaderUsed);
  }

  /**
   * Get the list of compared loaders from composite loader results.
   */
  getComparedLoaders(): string[] {
    if (!this.analysisResult?.compositeLoaderComparison?.loaderStats) {
      return [];
    }
    return Object.keys(this.analysisResult.compositeLoaderComparison.loaderStats);
  }

  /**
   * Get statistics for a specific loader from composite comparison.
   */
  getLoaderStats(loaderName: string): LoaderComparisonStats | null {
    return this.analysisResult?.compositeLoaderComparison?.loaderStats?.[loaderName] ?? null;
  }

  /**
   * Check if a loader was selected as the best by the composite loader.
   */
  isSelectedLoader(loaderName: string): boolean {
    return this.analysisResult?.compositeLoaderComparison?.selectedLoader === loaderName;
  }
}
