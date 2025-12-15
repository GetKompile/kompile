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

import { Component, Inject, OnInit, ChangeDetectionStrategy, ChangeDetectorRef, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators, FormControl, FormsModule } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatRadioModule } from '@angular/material/radio';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSliderModule } from '@angular/material/slider';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { Subscription, merge, startWith } from 'rxjs';
import { trigger, state, style, transition, animate } from '@angular/animations';

import {
  LoaderInfo,
  ChunkerInfo,
  AddSourceDialogResult,
  ChunkingConfig,
  ChunkingOptions,
  ChunkerStrategy,
  ChunkerStrategyInfo,
  ChunkingPreset,
  ChunkingRecommendation,
  DocumentAnalysis,
  LargeDocumentConfig,
  LargeDocumentMode,
  LargeDocumentModeInfo,
  CHUNKER_STRATEGIES,
  CHUNKING_PRESETS,
  DEFAULT_CHUNKING_CONFIG,
  DEFAULT_CHUNKING_OPTIONS,
  DEFAULT_LARGE_DOCUMENT_CONFIG,
  DOCUMENT_SIZE_THRESHOLDS,
  FILE_TYPE_CATEGORIES,
  LARGE_DOCUMENT_MODES,
  TokenizerOption,
  AVAILABLE_TOKENIZERS,
  RECOMMENDED_BATCH_SIZES,
  getRecommendedBatchSizes,
  SubprocessIngestConfig,
  DEFAULT_SUBPROCESS_CONFIG,
  HEAP_SIZE_OPTIONS,
  getRecommendedHeapSize,
  ProcessingMode
} from '../../../models/api-models';
import { HttpClient } from '@angular/common/http';
import { backendUrl } from '../../../services/base.service';
import { AdaptivePerformanceService, AdaptiveConfig, DEFAULT_ADAPTIVE_CONFIG } from '../../../services/adaptive-performance.service';
import { SubprocessConfigService, SubprocessConfigResponse } from '../../../services/subprocess-config.service';

export interface AddSourceDialogData {
  availableLoaders: LoaderInfo[];
  availableChunkers?: ChunkerInfo[];
}

// AddSourceDialogResult is now imported from api-models.ts

interface AddSourceFormModel {
  sourceType: FormControl<'file' | 'url'>;
  urlInput: FormControl<string | null>;
  fileNameInput: FormControl<string | null>;
  loaderSelect: FormControl<string | null>;
  rebuildIndex: FormControl<boolean>; // Added for the checkbox
}

@Component({
  selector: 'app-add-source-dialog',
  templateUrl: './add-source-dialog.component.html',
  styleUrls: ['./add-source-dialog.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatRadioModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatCheckboxModule,
    MatSliderModule,
    MatSlideToggleModule,
    MatChipsModule,
    MatTooltipModule,
    MatDividerModule
  ],
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
    ])
  ]
})
export class AddSourceDialogComponent implements OnInit, OnDestroy {
  addSourceForm: FormGroup<AddSourceFormModel>;
  selectedFile: File | null = null;
  selectedFiles: File[] = []; // Support multiple files
  fileErrorMessage: string | null = null;
  availableLoaders: LoaderInfo[] = [];
  isSubmitting: boolean = false;
  isSubmitButtonDisabled: boolean = true;
  isDragOver: boolean = false;

  // Chunking configuration
  showChunkingOptions: boolean = false;
  showAdvancedOptions: boolean = false;
  showLargeDocOptions: boolean = false;
  selectedPreset: string = 'default';
  chunkerStrategies: ChunkerStrategyInfo[] = CHUNKER_STRATEGIES;
  chunkingPresets: ChunkingPreset[] = CHUNKING_PRESETS;
  chunkingConfig: ChunkingConfig = {
    useCustomSettings: false,
    strategy: 'auto',
    options: { ...DEFAULT_CHUNKING_OPTIONS }
  };

  // Large document handling
  largeDocumentModes: LargeDocumentModeInfo[] = LARGE_DOCUMENT_MODES;
  largeDocConfig: LargeDocumentConfig = { ...DEFAULT_LARGE_DOCUMENT_CONFIG };

  // Tokenizer configuration
  availableTokenizers: TokenizerOption[] = AVAILABLE_TOKENIZERS;
  selectedTokenizer: string = 'default';
  enablePreTokenization: boolean = false;
  maxTokenLength: number = 512;
  showTokenizerOptions: boolean = true; // Show tokenizer options expanded by default

  // Document analysis and recommendations
  documentAnalysis: DocumentAnalysis | null = null;
  recommendations: ChunkingRecommendation[] = [];
  showRecommendations: boolean = true;
  dismissedRecommendations: Set<string> = new Set();

  // Batch size recommendations
  showBatchRecommendations: boolean = true;
  systemMemoryMB: number = 8192; // Default to 8GB
  recommendedBatchSizes: typeof RECOMMENDED_BATCH_SIZES[keyof typeof RECOMMENDED_BATCH_SIZES] = RECOMMENDED_BATCH_SIZES.MEDIUM_MEMORY;
  embeddingBatchSize: number = 8;
  isLoadingSystemInfo: boolean = false;

  // Adaptive performance mode
  adaptiveMode: boolean = false;
  showAdaptiveOptions: boolean = false;
  adaptiveConfig: AdaptiveConfig = { ...DEFAULT_ADAPTIVE_CONFIG };

  // Subprocess configuration
  showSubprocessOptions: boolean = false;
  subprocessConfig: SubprocessIngestConfig = { ...DEFAULT_SUBPROCESS_CONFIG };
  heapSizeOptions: string[] = HEAP_SIZE_OPTIONS;
  isLoadingSubprocessConfig: boolean = false;
  subprocessConfigLoaded: boolean = false;

  // Processing mode selection (per-request override)
  selectedProcessingMode: 'auto' | 'subprocess' | 'inprocess' = 'auto';
  processingModeOptions: Array<{value: 'auto' | 'subprocess' | 'inprocess', label: string, description: string, icon: string}> = [
    { value: 'auto', label: 'Auto (Use Global Setting)', description: 'Use the global subprocess configuration to decide processing mode', icon: 'settings_suggest' },
    { value: 'inprocess', label: 'In-Process (Same JVM)', description: 'Process in the main application - faster startup, better for debugging', icon: 'memory' },
    { value: 'subprocess', label: 'Subprocess (Isolated JVM)', description: 'Process in separate JVM - crash isolation, better stability', icon: 'launch' }
  ];

  /**
   * Get the description for the currently selected processing mode.
   */
  getSelectedProcessingModeDescription(): string {
    const mode = this.processingModeOptions.find(m => m.value === this.selectedProcessingMode);
    return mode?.description || '';
  }

  private subscriptions: Subscription = new Subscription();

  constructor(
    public dialogRef: MatDialogRef<AddSourceDialogComponent, AddSourceDialogResult>,
    @Inject(MAT_DIALOG_DATA) public data: AddSourceDialogData,
    private fb: FormBuilder,
    private cdr: ChangeDetectorRef,
    private http: HttpClient,
    private adaptivePerformanceService: AdaptivePerformanceService,
    private subprocessConfigService: SubprocessConfigService
  ) {
    this.availableLoaders = this.data.availableLoaders;
    this.addSourceForm = this.fb.group<AddSourceFormModel>({
      sourceType: new FormControl<'file' | 'url'>('file', { nonNullable: true, validators: Validators.required }),
      urlInput: new FormControl('', { validators: [Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)] }),
      fileNameInput: new FormControl(''),
      loaderSelect: new FormControl(''),
      rebuildIndex: new FormControl(false, { nonNullable: true }) // Initialize checkbox form control
    });
  }

  ngOnInit(): void {
    // Merge backend chunkers with static list if available
    this.mergeBackendChunkers();

    // Fetch system resources for batch size recommendations
    this.fetchSystemResources();

    // Fetch current subprocess configuration
    this.fetchSubprocessConfig();

    this.updateValidatorsBasedOnSourceType();

    const sourceTypeControl = this.addSourceForm.controls.sourceType;

    this.subscriptions.add(
      sourceTypeControl.valueChanges.subscribe(() => {
        this.updateValidatorsBasedOnSourceType();
      })
    );

    this.subscriptions.add(
      merge(
        this.addSourceForm.statusChanges,
        sourceTypeControl.valueChanges
      ).pipe(startWith(null))
        .subscribe(() => {
          this.updateSubmitButtonState();
        })
    );
    this.updateSubmitButtonState();
  }

  /**
   * Merges backend chunkers with the static CHUNKER_STRATEGIES list.
   * Backend chunkers that aren't in the static list are added dynamically.
   */
  private mergeBackendChunkers(): void {
    if (!this.data.availableChunkers || this.data.availableChunkers.length === 0) {
      return;
    }

    // Get existing strategy IDs
    const existingIds = new Set(this.chunkerStrategies.map(s => s.id));

    // Add backend chunkers that aren't already in the list
    for (const backendChunker of this.data.availableChunkers) {
      // Skip noop chunkers
      if (backendChunker.name.toLowerCase().includes('noop') ||
          backendChunker.name.toLowerCase().includes('no-op')) {
        continue;
      }

      if (!existingIds.has(backendChunker.name as ChunkerStrategy)) {
        // Create a new strategy info for this backend chunker
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

  /**
   * Formats a chunker name for display (e.g., 'spring_token' -> 'Spring Token')
   */
  private formatChunkerName(name: string): string {
    return name
      .replace(/_/g, ' ')
      .replace(/-/g, ' ')
      .split(' ')
      .map(word => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ');
  }

  /**
   * Returns an appropriate icon for a chunker based on its name
   */
  private getIconForChunker(name: string): string {
    const lowerName = name.toLowerCase();
    if (lowerName.includes('sentence')) return 'short_text';
    if (lowerName.includes('token')) return 'tag';
    if (lowerName.includes('markdown')) return 'code';
    if (lowerName.includes('recursive') || lowerName.includes('character')) return 'account_tree';
    return 'content_cut';
  }

  private updateValidatorsBasedOnSourceType(): void {
    const sourceType = this.addSourceForm.controls.sourceType.value;
    const urlControl = this.addSourceForm.controls.urlInput;

    if (sourceType !== 'file') {
      this.selectedFile = null;
      this.selectedFiles = [];
      this.fileErrorMessage = null;
      const fileInput = document.getElementById('dialogInternalFileInput') as HTMLInputElement;
      if (fileInput) fileInput.value = '';
    }

    if (sourceType === 'file') {
      urlControl.clearValidators();
      urlControl.setValidators([Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)]);
    } else { // 'url'
      urlControl.setValidators([
        Validators.required,
        Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)
      ]);
    }
    urlControl.updateValueAndValidity({ emitEvent: false });
    this.updateSubmitButtonState();
    this.cdr.markForCheck();
  }

  private updateSubmitButtonState(): void {
    const sourceType = this.addSourceForm.controls.sourceType.value;
    let isValid = false;
    if (sourceType === 'file') {
      isValid = this.selectedFiles.length > 0;
      if (isValid) {
        this.fileErrorMessage = null;
      }
    } else if (sourceType === 'url') {
      isValid = this.addSourceForm.controls.urlInput.valid;
    }
    this.isSubmitButtonDisabled = !isValid || this.isSubmitting;
    this.cdr.markForCheck();
  }

  onFileSelectedChange(event: Event): void {
    const element = event.target as HTMLInputElement;
    this.fileErrorMessage = null;
    if (element.files && element.files.length > 0) {
      // Convert FileList to array and add to selectedFiles
      this.selectedFiles = Array.from(element.files);
      this.selectedFile = this.selectedFiles[0]; // Keep for backwards compatibility
      if (this.addSourceForm.controls.sourceType.value !== 'file') {
        this.addSourceForm.controls.sourceType.setValue('file');
      } else {
        this.updateSubmitButtonState();
      }
      // Analyze documents for recommendations
      this.analyzeDocuments();
    } else {
      this.selectedFile = null;
      this.selectedFiles = [];
      this.documentAnalysis = null;
      this.recommendations = [];
      this.updateSubmitButtonState();
    }
  }

  removeFile(index: number): void {
    this.selectedFiles.splice(index, 1);
    this.selectedFile = this.selectedFiles.length > 0 ? this.selectedFiles[0] : null;
    this.updateSubmitButtonState();
    // Re-analyze after removing file
    this.analyzeDocuments();
    this.cdr.markForCheck();
  }

  triggerFileInput(): void {
    if (this.isSubmitting) return;
    const fileInput = document.getElementById('dialogInternalFileInput') as HTMLInputElement;
    if (fileInput) {
      fileInput.click();
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    if (!this.isSubmitting) {
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

    if (this.isSubmitting) return;

    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      // Add dropped files to existing selection
      const newFiles = Array.from(files);
      this.selectedFiles = [...this.selectedFiles, ...newFiles];
      this.selectedFile = this.selectedFiles[0];
      this.fileErrorMessage = null;

      if (this.addSourceForm.controls.sourceType.value !== 'file') {
        this.addSourceForm.controls.sourceType.setValue('file');
      } else {
        this.updateSubmitButtonState();
      }
      // Analyze documents for recommendations
      this.analyzeDocuments();
      this.cdr.markForCheck();
    }
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
      'ppt': 'slideshow',
      'pptx': 'slideshow',
      'md': 'article',
      'rtf': 'description',
      'odt': 'description',
      'ods': 'table_chart',
      'odp': 'slideshow',
      'epub': 'menu_book',
      'eml': 'email',
      'msg': 'email'
    };
    return iconMap[ext] || 'insert_drive_file';
  }

  getFileIconClass(fileName: string): string {
    const ext = fileName.split('.').pop()?.toLowerCase() || '';
    const classMap: { [key: string]: string } = {
      'pdf': 'icon-pdf',
      'doc': 'icon-doc',
      'docx': 'icon-doc',
      'txt': 'icon-txt',
      'html': 'icon-code',
      'htm': 'icon-code',
      'xml': 'icon-code',
      'json': 'icon-code',
      'csv': 'icon-spreadsheet',
      'xls': 'icon-spreadsheet',
      'xlsx': 'icon-spreadsheet',
      'ppt': 'icon-presentation',
      'pptx': 'icon-presentation',
      'md': 'icon-txt',
      'eml': 'icon-email',
      'msg': 'icon-email'
    };
    return classMap[ext] || 'icon-default';
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) {
      return `${bytes} B`;
    } else if (bytes < 1024 * 1024) {
      return `${(bytes / 1024).toFixed(1)} KB`;
    } else {
      return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    }
  }

  getTotalFileSize(): string {
    const totalBytes = this.selectedFiles.reduce((sum, file) => sum + file.size, 0);
    if (totalBytes < 1024) {
      return `${totalBytes} B`;
    } else if (totalBytes < 1024 * 1024) {
      return `${(totalBytes / 1024).toFixed(2)} KB`;
    } else {
      return `${(totalBytes / (1024 * 1024)).toFixed(2)} MB`;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CHUNKING CONFIGURATION METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  toggleChunkingSection(): void {
    this.showChunkingOptions = !this.showChunkingOptions;
    this.cdr.markForCheck();
  }

  toggleTokenizerSection(): void {
    this.showTokenizerOptions = !this.showTokenizerOptions;
    this.cdr.markForCheck();
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

  toggleAdvancedOptions(): void {
    this.showAdvancedOptions = !this.showAdvancedOptions;
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
    if (this.isSubmitting) return;

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
    if (this.selectedPreset === 'custom') {
      return 'Custom';
    }
    const preset = this.chunkingPresets.find(p => p.id === this.selectedPreset);
    return preset?.name || 'Default';
  }

  estimateChunks(): number {
    if (this.selectedFiles.length === 0) return 0;

    const totalBytes = this.selectedFiles.reduce((sum, file) => sum + file.size, 0);

    // Text files are roughly 1 byte per character
    // Binary files (PDF, DOCX) typically yield less text
    const estimatedTextRatio = 0.5; // Conservative estimate for binary formats
    const estimatedTextChars = totalBytes * estimatedTextRatio;

    // Different estimation for sentence-based vs character-based chunking
    if (this.chunkingConfig.strategy === 'opennlp_sentence') {
      // Sentence chunking: estimate ~80-100 chars per sentence on average
      const avgSentenceLength = 90;
      return Math.ceil(estimatedTextChars / avgSentenceLength);
    } else {
      // Character-based chunking: account for overlap
      const effectiveChunkSize = this.chunkingConfig.options.chunkSize - this.chunkingConfig.options.overlap;
      if (effectiveChunkSize <= 0) return 0;
      return Math.ceil(estimatedTextChars / effectiveChunkSize);
    }
  }

  private buildChunkerOptions(): { [key: string]: any } {
    const options: { [key: string]: any } = {
      chunkSize: this.chunkingConfig.options.chunkSize,
      overlap: this.chunkingConfig.options.overlap,
      preserveParagraphs: this.chunkingConfig.options.preserveParagraphs
    };

    if (this.chunkingConfig.options.minChunkSize) {
      options['minChunkSize'] = this.chunkingConfig.options.minChunkSize;
    }

    if (this.chunkingConfig.options.maxChunkSize) {
      options['maxChunkSize'] = this.chunkingConfig.options.maxChunkSize;
    }

    // Strategy-specific options
    if (this.chunkingConfig.strategy === 'opennlp_sentence' && this.chunkingConfig.options.language) {
      options['language'] = this.chunkingConfig.options.language;
    }

    if (this.chunkingConfig.strategy === 'custom_markdown' && this.chunkingConfig.options.splitOnHeadings !== undefined) {
      options['splitOnHeadings'] = this.chunkingConfig.options.splitOnHeadings;
    }

    return options;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // DOCUMENT ANALYSIS & RECOMMENDATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  analyzeDocuments(): void {
    if (this.selectedFiles.length === 0) {
      this.documentAnalysis = null;
      this.recommendations = [];
      return;
    }

    const totalSize = this.selectedFiles.reduce((sum, f) => sum + f.size, 0);
    const fileTypes: { [ext: string]: number } = {};

    let largestFile: { name: string; size: number } | null = null;

    this.selectedFiles.forEach(file => {
      const ext = file.name.split('.').pop()?.toLowerCase() || 'unknown';
      fileTypes[ext] = (fileTypes[ext] || 0) + 1;

      if (!largestFile || file.size > largestFile.size) {
        largestFile = { name: file.name, size: file.size };
      }
    });

    // Find dominant file type
    let dominantType: string | null = null;
    let maxCount = 0;
    Object.entries(fileTypes).forEach(([ext, count]) => {
      if (count > maxCount) {
        maxCount = count;
        dominantType = ext;
      }
    });

    const hasLargeFiles = this.selectedFiles.some(f => f.size > DOCUMENT_SIZE_THRESHOLDS.LARGE);
    const hasVeryLargeFiles = this.selectedFiles.some(f => f.size > DOCUMENT_SIZE_THRESHOLDS.VERY_LARGE);

    // Estimate processing
    const estimatedChunks = this.estimateChunks();
    const estimatedProcessingTime = this.estimateProcessingTime(totalSize, estimatedChunks);

    this.documentAnalysis = {
      totalSize,
      fileCount: this.selectedFiles.length,
      averageSize: totalSize / this.selectedFiles.length,
      largestFile,
      fileTypes,
      dominantType,
      hasLargeFiles,
      hasVeryLargeFiles,
      estimatedChunks,
      estimatedProcessingTime,
      memoryWarning: hasVeryLargeFiles || totalSize > DOCUMENT_SIZE_THRESHOLDS.VERY_LARGE
    };

    this.generateRecommendations();
    this.cdr.markForCheck();
  }

  private estimateProcessingTime(totalSize: number, chunks: number): string {
    // Rough estimates based on typical processing speeds
    // These are conservative estimates - actual times vary based on hardware and document complexity
    const extractionTimeMs = totalSize / 50000; // ~50KB/ms for text extraction (PDFs are slower)
    const chunkingTimeMs = chunks * 0.5; // ~0.5ms per chunk (chunking is fast)
    const indexingTimeMs = chunks * 10; // ~10ms per chunk for indexing (includes any embedding)
    const totalMs = extractionTimeMs + chunkingTimeMs + indexingTimeMs;

    if (totalMs < 1000) return '< 1 second';
    if (totalMs < 60000) return `~${Math.ceil(totalMs / 1000)} seconds`;
    if (totalMs < 3600000) return `~${Math.ceil(totalMs / 60000)} minutes`;
    return `~${(totalMs / 3600000).toFixed(1)} hours`;
  }

  private generateRecommendations(): void {
    this.recommendations = [];
    if (!this.documentAnalysis) return;

    const { totalSize, fileCount, dominantType, hasLargeFiles, hasVeryLargeFiles, estimatedChunks, fileTypes } = this.documentAnalysis;

    // Critical: Very large files warning
    if (hasVeryLargeFiles) {
      this.recommendations.push({
        id: 'very-large-files',
        severity: 'critical',
        title: 'Very Large Files Detected',
        description: `Files over 50MB may cause memory issues. Consider using streaming or batch processing mode.`,
        action: {
          label: 'Enable Streaming',
          largeDocConfig: { mode: 'streaming', enableStreaming: true }
        },
        icon: 'warning'
      });
    }

    // Warning: Large files
    if (hasLargeFiles && !hasVeryLargeFiles) {
      this.recommendations.push({
        id: 'large-files',
        severity: 'warning',
        title: 'Large Files Detected',
        description: 'Files over 10MB detected. Consider batch processing for better stability.',
        action: {
          label: 'Use Batch Mode',
          largeDocConfig: { mode: 'batch', batchSize: 25 }
        },
        icon: 'info'
      });
    }

    // Many chunks warning
    if (estimatedChunks > 1000) {
      this.recommendations.push({
        id: 'many-chunks',
        severity: 'warning',
        title: 'High Chunk Count',
        description: `Estimated ${estimatedChunks.toLocaleString()} chunks. Consider larger chunk sizes for faster indexing.`,
        action: {
          label: 'Use Large Chunks',
          preset: 'large-chunks'
        },
        icon: 'layers'
      });
    }

    // File type recommendations
    if (dominantType && FILE_TYPE_CATEGORIES[dominantType]) {
      const typeInfo = FILE_TYPE_CATEGORIES[dominantType];

      if (typeInfo.category === 'markdown' && this.chunkingConfig.strategy !== 'custom_markdown') {
        this.recommendations.push({
          id: 'markdown-strategy',
          severity: 'suggestion',
          title: 'Markdown Files Detected',
          description: 'Use the Markdown chunker to preserve heading structure.',
          action: {
            label: 'Use Markdown Chunker',
            strategy: 'custom_markdown',
            options: { splitOnHeadings: true }
          },
          icon: 'code'
        });
      }

      if ((typeInfo.category === 'document' || typeInfo.category === 'email') &&
          this.chunkingConfig.strategy === 'auto') {
        this.recommendations.push({
          id: 'sentence-strategy',
          severity: 'suggestion',
          title: `${typeInfo.description} Files Detected`,
          description: 'Sentence-based chunking may provide better results for natural language documents.',
          action: {
            label: 'Use Sentence Chunker',
            strategy: 'opennlp_sentence'
          },
          icon: 'psychology'
        });
      }
    }

    // Multiple file types
    const typeCount = Object.keys(fileTypes).length;
    if (typeCount > 3) {
      this.recommendations.push({
        id: 'mixed-types',
        severity: 'info',
        title: 'Mixed File Types',
        description: `${typeCount} different file types detected. Auto-detect mode recommended.`,
        action: {
          label: 'Use Auto-detect',
          strategy: 'auto'
        },
        icon: 'auto_awesome'
      });
    }

    // Batch upload optimization
    if (fileCount > 10) {
      this.recommendations.push({
        id: 'batch-upload',
        severity: 'info',
        title: 'Multiple Files',
        description: `${fileCount} files selected. Processing will be batched for efficiency.`,
        icon: 'folder'
      });
    }

    // Small chunks for precise retrieval
    if (totalSize < DOCUMENT_SIZE_THRESHOLDS.SMALL && this.chunkingConfig.options.chunkSize > 500) {
      this.recommendations.push({
        id: 'small-docs-chunks',
        severity: 'suggestion',
        title: 'Small Documents',
        description: 'For smaller documents, smaller chunks may improve retrieval precision.',
        action: {
          label: 'Use Small Chunks',
          preset: 'small-chunks'
        },
        icon: 'zoom_in'
      });
    }

    // Add embedding batch size recommendation based on estimated chunks
    this.updateBatchSizeRecommendation();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // BATCH SIZE RECOMMENDATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  // ═══════════════════════════════════════════════════════════════════════════════
  // SUBPROCESS CONFIGURATION METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Fetches current subprocess configuration from the backend.
   */
  private fetchSubprocessConfig(): void {
    this.isLoadingSubprocessConfig = true;
    this.subprocessConfigService.getConfiguration().subscribe({
      next: (config: SubprocessConfigResponse) => {
        this.subprocessConfig = {
          enabled: config.enabled,
          heapSize: config.heapSize,
          offHeapMaxBytes: config.offHeapMaxBytes || '',
          timeoutMinutes: config.timeoutMinutes,
          heartbeatIntervalSeconds: config.heartbeatIntervalSeconds,
          staleThresholdSeconds: config.staleThresholdSeconds
        };
        // Update recommended heap size based on available memory
        if (config.availableMemoryMb) {
          const recommended = getRecommendedHeapSize(config.availableMemoryMb);
          // If user hasn't changed from default and system suggests different, use recommended
          if (this.subprocessConfig.heapSize === '4g' && recommended !== '4g') {
            this.subprocessConfig.heapSize = recommended;
          }
        }
        this.subprocessConfigLoaded = true;
        this.isLoadingSubprocessConfig = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.warn('Failed to fetch subprocess config, using defaults:', err);
        this.subprocessConfigLoaded = true;
        this.isLoadingSubprocessConfig = false;
        this.cdr.markForCheck();
      }
    });
  }

  /**
   * Toggle subprocess configuration section visibility.
   */
  toggleSubprocessSection(): void {
    this.showSubprocessOptions = !this.showSubprocessOptions;
    this.cdr.markForCheck();
  }

  /**
   * Toggle subprocess enabled state - updates local state immediately and persists to backend.
   */
  toggleSubprocessEnabled(enabled: boolean): void {
    console.log('toggleSubprocessEnabled called with:', enabled);

    // Update local state IMMEDIATELY for responsive UI
    this.subprocessConfig.enabled = enabled;
    this.cdr.detectChanges(); // Force immediate UI update

    // Persist to backend
    const request = enabled
      ? this.subprocessConfigService.enable()
      : this.subprocessConfigService.disable();

    request.subscribe({
      next: (config: SubprocessConfigResponse) => {
        // Sync all config values from backend response
        this.subprocessConfig.enabled = config.enabled;
        this.subprocessConfig.heapSize = config.heapSize;
        this.subprocessConfig.offHeapMaxBytes = config.offHeapMaxBytes || '';
        this.subprocessConfig.timeoutMinutes = config.timeoutMinutes;
        this.subprocessConfig.heartbeatIntervalSeconds = config.heartbeatIntervalSeconds;
        this.subprocessConfig.staleThresholdSeconds = config.staleThresholdSeconds;
        console.log('Subprocess mode persisted:', config.enabled ? 'enabled' : 'disabled');
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to persist subprocess mode:', err);
        // Revert local state on error
        this.subprocessConfig.enabled = !enabled;
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Get the recommended heap size based on system memory.
   */
  getRecommendedHeapSizeLabel(): string {
    const recommended = getRecommendedHeapSize(this.systemMemoryMB);
    return recommended;
  }

  /**
   * Check if a heap size is the recommended one.
   */
  isRecommendedHeapSize(size: string): boolean {
    return size === getRecommendedHeapSize(this.systemMemoryMB);
  }

  /**
   * Get subprocess mode description based on enabled state.
   */
  getSubprocessModeDescription(): string {
    if (this.subprocessConfig.enabled) {
      return 'Document ingestion runs in isolated JVM processes for crash protection';
    }
    return 'Document ingestion runs in the main process (no crash isolation)';
  }

  /**
   * Fetches system resources to determine optimal batch sizes.
   */
  private fetchSystemResources(): void {
    this.isLoadingSystemInfo = true;
    this.http.get<any>(`${backendUrl}/system/resources`).subscribe({
      next: (response) => {
        // Extract system memory from response
        if (response?.memory?.system?.totalMB) {
          this.systemMemoryMB = response.memory.system.totalMB;
        } else if (response?.memory?.jvm?.maxMB) {
          // Fallback to JVM max memory
          this.systemMemoryMB = response.memory.jvm.maxMB;
        }

        // Update batch size recommendations based on available memory
        this.recommendedBatchSizes = getRecommendedBatchSizes(this.systemMemoryMB);
        this.embeddingBatchSize = this.recommendedBatchSizes.embeddingBatch;
        this.isLoadingSystemInfo = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.warn('Failed to fetch system resources, using defaults:', err);
        // Use defaults (medium memory settings)
        this.recommendedBatchSizes = RECOMMENDED_BATCH_SIZES.MEDIUM_MEMORY;
        this.embeddingBatchSize = this.recommendedBatchSizes.embeddingBatch;
        this.isLoadingSystemInfo = false;
        this.cdr.markForCheck();
      }
    });
  }

  /**
   * Updates batch size recommendation based on document analysis.
   */
  private updateBatchSizeRecommendation(): void {
    if (!this.documentAnalysis) return;

    const { estimatedChunks, hasVeryLargeFiles, memoryWarning } = this.documentAnalysis;

    // Adjust embedding batch size based on workload
    if (memoryWarning || hasVeryLargeFiles) {
      // Use conservative batch size for memory-constrained scenarios
      this.embeddingBatchSize = Math.min(this.recommendedBatchSizes.embeddingBatch, 4);
    } else if (estimatedChunks > 5000) {
      // Large workload: use maximum recommended batch for throughput
      this.embeddingBatchSize = this.recommendedBatchSizes.maxEmbeddingBatch;
    } else if (estimatedChunks > 1000) {
      // Medium workload: use optimal batch size
      this.embeddingBatchSize = this.recommendedBatchSizes.embeddingBatch;
    } else {
      // Small workload: can use smaller batch for faster startup
      this.embeddingBatchSize = Math.max(4, Math.floor(this.recommendedBatchSizes.embeddingBatch / 2));
    }
  }

  /**
   * Gets the memory tier label based on system memory.
   */
  getMemoryTierLabel(): string {
    if (this.systemMemoryMB < 4096) return 'Low Memory (<4GB)';
    if (this.systemMemoryMB < 8192) return 'Medium Memory (4-8GB)';
    if (this.systemMemoryMB < 16384) return 'High Memory (8-16GB)';
    return 'Very High Memory (>16GB)';
  }

  /**
   * Gets the memory tier class for styling.
   */
  getMemoryTierClass(): string {
    if (this.systemMemoryMB < 4096) return 'memory-low';
    if (this.systemMemoryMB < 8192) return 'memory-medium';
    if (this.systemMemoryMB < 16384) return 'memory-high';
    return 'memory-very-high';
  }

  /**
   * Gets workload description based on estimated chunks.
   */
  getWorkloadDescription(): string {
    if (!this.documentAnalysis) return 'No documents selected';
    const chunks = this.documentAnalysis.estimatedChunks;
    if (chunks < 100) return 'Light workload';
    if (chunks < 1000) return 'Moderate workload';
    if (chunks < 5000) return 'Heavy workload';
    return 'Very heavy workload';
  }

  toggleBatchRecommendations(): void {
    this.showBatchRecommendations = !this.showBatchRecommendations;
    this.cdr.markForCheck();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ADAPTIVE PERFORMANCE MODE
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Toggle adaptive performance mode.
   */
  toggleAdaptiveMode(): void {
    this.adaptiveMode = !this.adaptiveMode;

    if (this.adaptiveMode) {
      // Initialize with recommended config based on system memory
      const recommendedConfig = this.adaptivePerformanceService.getRecommendedConfig(this.systemMemoryMB);
      this.adaptiveConfig = {
        ...DEFAULT_ADAPTIVE_CONFIG,
        ...recommendedConfig,
        enabled: true
      };
    } else {
      this.adaptiveConfig.enabled = false;
    }

    this.cdr.markForCheck();
  }

  /**
   * Toggle adaptive options panel visibility.
   */
  toggleAdaptiveOptions(): void {
    this.showAdaptiveOptions = !this.showAdaptiveOptions;
    this.cdr.markForCheck();
  }

  /**
   * Update adaptive config with a specific value.
   */
  updateAdaptiveConfig(key: keyof AdaptiveConfig, value: any): void {
    (this.adaptiveConfig as any)[key] = value;
    this.cdr.markForCheck();
  }

  /**
   * Apply a preset adaptive configuration.
   */
  applyAdaptivePreset(preset: 'conservative' | 'balanced' | 'aggressive'): void {
    const baseConfig = this.adaptivePerformanceService.getRecommendedConfig(this.systemMemoryMB);

    switch (preset) {
      case 'conservative':
        this.adaptiveConfig = {
          ...DEFAULT_ADAPTIVE_CONFIG,
          ...baseConfig,
          enabled: true,
          targetMemoryPercent: 60,
          criticalMemoryPercent: 80,
          minEmbeddingBatch: 2,
          maxEmbeddingBatch: Math.max(8, (baseConfig.maxEmbeddingBatch || 32) / 2),
          adjustmentCooldownMs: 20000
        };
        break;

      case 'balanced':
        this.adaptiveConfig = {
          ...DEFAULT_ADAPTIVE_CONFIG,
          ...baseConfig,
          enabled: true
        };
        break;

      case 'aggressive':
        this.adaptiveConfig = {
          ...DEFAULT_ADAPTIVE_CONFIG,
          ...baseConfig,
          enabled: true,
          targetMemoryPercent: 80,
          criticalMemoryPercent: 92,
          maxEmbeddingBatch: Math.min(64, (baseConfig.maxEmbeddingBatch || 32) * 1.5),
          adjustmentCooldownMs: 10000
        };
        break;
    }

    this.cdr.markForCheck();
  }

  /**
   * Get description for an adaptive preset.
   */
  getAdaptivePresetDescription(preset: 'conservative' | 'balanced' | 'aggressive'): string {
    switch (preset) {
      case 'conservative':
        return 'Lower memory targets, smaller batches. Best for limited RAM or shared systems.';
      case 'balanced':
        return 'Recommended settings based on your system memory. Good balance of speed and stability.';
      case 'aggressive':
        return 'Higher throughput, uses more memory. Best for dedicated processing systems.';
    }
  }

  applyRecommendation(rec: ChunkingRecommendation): void {
    if (!rec.action) return;

    if (rec.action.preset) {
      this.applyPreset(rec.action.preset);
    }

    if (rec.action.strategy) {
      this.selectStrategy(rec.action.strategy);
    }

    if (rec.action.options) {
      this.chunkingConfig.options = {
        ...this.chunkingConfig.options,
        ...rec.action.options
      };
      this.chunkingConfig.useCustomSettings = true;
      this.selectedPreset = 'custom';
    }

    if (rec.action.largeDocConfig) {
      this.largeDocConfig = {
        ...this.largeDocConfig,
        ...rec.action.largeDocConfig
      };
      this.showLargeDocOptions = true;
    }

    this.dismissRecommendation(rec.id);
    this.cdr.markForCheck();
  }

  dismissRecommendation(id: string): void {
    this.dismissedRecommendations.add(id);
    this.cdr.markForCheck();
  }

  getVisibleRecommendations(): ChunkingRecommendation[] {
    return this.recommendations.filter(r => !this.dismissedRecommendations.has(r.id));
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // LARGE DOCUMENT HANDLING
  // ═══════════════════════════════════════════════════════════════════════════════

  toggleLargeDocOptions(): void {
    this.showLargeDocOptions = !this.showLargeDocOptions;
    this.cdr.markForCheck();
  }

  selectLargeDocMode(mode: LargeDocumentMode): void {
    if (this.isSubmitting) return;

    this.largeDocConfig.mode = mode;

    // Apply mode-specific defaults
    switch (mode) {
      case 'streaming':
        this.largeDocConfig.enableStreaming = true;
        this.largeDocConfig.batchSize = 25;
        break;
      case 'batch':
        this.largeDocConfig.enableStreaming = false;
        this.largeDocConfig.batchSize = 50;
        break;
      case 'hierarchical':
        this.largeDocConfig.enableHierarchical = true;
        this.largeDocConfig.createSummaries = true;
        this.largeDocConfig.batchSize = 25;
        break;
      default:
        this.largeDocConfig.enableStreaming = false;
        this.largeDocConfig.enableHierarchical = false;
    }

    this.cdr.markForCheck();
  }

  getMemoryUsageClass(usage: 'low' | 'medium' | 'high'): string {
    return `memory-${usage}`;
  }

  getSpeedClass(speed: 'fast' | 'medium' | 'slow'): string {
    return `speed-${speed}`;
  }

  onCancelDialog(): void {
    if (!this.isSubmitting) {
      this.dialogRef.close();
    }
  }

  private checkFormValidityForAction(): boolean {
    const sourceType = this.addSourceForm.controls.sourceType.value;
    if (sourceType === 'file') {
      if (this.selectedFiles.length === 0) {
        this.fileErrorMessage = "At least one file must be selected to upload.";
        return false;
      }
      this.fileErrorMessage = null;
      return true;
    } else if (sourceType === 'url') {
      this.addSourceForm.controls.urlInput.markAsTouched();
      return this.addSourceForm.controls.urlInput.valid;
    }
    return false;
  }

  onSubmitDialog(): void {
    Object.values(this.addSourceForm.controls).forEach(control => {
      control.markAsTouched();
    });

    if (this.addSourceForm.controls.sourceType.value === 'file') {
      if (this.selectedFiles.length === 0) {
        this.fileErrorMessage = "At least one file must be selected to upload.";
      } else {
        this.fileErrorMessage = null;
      }
    }
    this.updateSubmitButtonState();
    this.cdr.markForCheck();

    if (!this.checkFormValidityForAction()) {
      return;
    }

    this.isSubmitting = true;
    this.updateSubmitButtonState();

    const formValues = this.addSourceForm.getRawValue();
    const result: AddSourceDialogResult = {
      selectedLoader: formValues.loaderSelect || undefined,
      rebuildIndex: formValues.rebuildIndex
    };

    // Include chunking configuration if custom settings are used
    if (this.chunkingConfig.useCustomSettings || this.chunkingConfig.strategy !== 'auto') {
      result.chunkerName = this.chunkingConfig.strategy === 'auto' ? undefined : this.chunkingConfig.strategy;
      result.chunkerOptions = this.buildChunkerOptions();
    }

    // Include tokenizer configuration if enabled
    if (this.enablePreTokenization && this.selectedTokenizer !== 'default') {
      result.tokenizerModel = this.selectedTokenizer;
      result.maxTokenLength = this.maxTokenLength;
      result.enablePreTokenization = this.enablePreTokenization;
    }

    // Include large document handling config if not using standard mode
    if (this.largeDocConfig.mode !== 'standard' ||
        this.largeDocConfig.enableStreaming ||
        this.largeDocConfig.skipEmbeddedMedia) {
      result.largeDocumentConfig = {
        mode: this.largeDocConfig.mode,
        enableStreaming: this.largeDocConfig.enableStreaming,
        batchSize: this.largeDocConfig.batchSize,
        enableHierarchical: this.largeDocConfig.enableHierarchical,
        createSummaries: this.largeDocConfig.createSummaries,
        optimizeExtraction: this.largeDocConfig.optimizeExtraction,
        skipEmbeddedMedia: this.largeDocConfig.skipEmbeddedMedia
      };
    }

    // Include adaptive performance config if enabled
    if (this.adaptiveMode) {
      result.adaptivePerformanceConfig = {
        enabled: this.adaptiveConfig.enabled,
        targetMemoryPercent: this.adaptiveConfig.targetMemoryPercent,
        criticalMemoryPercent: this.adaptiveConfig.criticalMemoryPercent,
        minEmbeddingBatch: this.adaptiveConfig.minEmbeddingBatch,
        maxEmbeddingBatch: this.adaptiveConfig.maxEmbeddingBatch,
        minIndexBatch: this.adaptiveConfig.minIndexBatch,
        maxIndexBatch: this.adaptiveConfig.maxIndexBatch,
        checkIntervalMs: this.adaptiveConfig.checkIntervalMs,
        adjustmentCooldownMs: this.adaptiveConfig.adjustmentCooldownMs
      };

      // Activate the adaptive performance service
      this.adaptivePerformanceService.updateConfig(this.adaptiveConfig);
      this.adaptivePerformanceService.startMonitoring();
    }

    // Include subprocess configuration
    result.subprocessConfig = {
      enabled: this.subprocessConfig.enabled,
      heapSize: this.subprocessConfig.heapSize,
      offHeapMaxBytes: this.subprocessConfig.offHeapMaxBytes,
      timeoutMinutes: this.subprocessConfig.timeoutMinutes,
      heartbeatIntervalSeconds: this.subprocessConfig.heartbeatIntervalSeconds,
      staleThresholdSeconds: this.subprocessConfig.staleThresholdSeconds
    };

    // Include per-request processing mode override
    result.processingMode = this.selectedProcessingMode;

    if (formValues.sourceType === 'file' && this.selectedFiles.length > 0) {
      result.file = this.selectedFiles[0]; // Keep for backwards compatibility
      result.files = this.selectedFiles; // Include all files for batch upload
    } else if (formValues.sourceType === 'url') {
      result.url = formValues.urlInput ?? undefined;
      result.fileName = formValues.fileNameInput || undefined;
    }

    // DEBUG: Log what we're sending
    console.log('=== ADD SOURCE DIALOG DEBUG ===');
    console.log('chunkingConfig.strategy:', this.chunkingConfig.strategy);
    console.log('chunkingConfig.useCustomSettings:', this.chunkingConfig.useCustomSettings);
    console.log('result.chunkerName:', result.chunkerName);
    console.log('result.selectedLoader:', result.selectedLoader);
    console.log('Full result:', JSON.stringify(result, null, 2));
    console.log('=== END DEBUG ===');

    this.dialogRef.close(result);
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }
}
