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

import { Component, OnInit, OnDestroy, ViewChild, AfterViewInit, ChangeDetectorRef } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { MatTableDataSource } from '@angular/material/table';
import { MatPaginator } from '@angular/material/paginator';
import { MatSort } from '@angular/material/sort';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subscription } from 'rxjs';
import { OcrService } from '../../../services/ocr.service';
import { OcrStatus, OcrConfig, OcrModelInfo } from '../../../models/ocr-models';

export interface LoaderDebugInfo {
  name: string;
  className: string;
  isNoOp: boolean;
  supportsFile: boolean;
  supportReason: string;
}

export interface ChunkerDebugInfo {
  name: string;
  className: string;
  isNoOp: boolean;
  reason: string;
}

export interface DocumentDebugInfo {
  id: string;
  content: string;
  contentLength: number;
  hasContent: boolean;
  metadata: any;
  contentLines: string[];
  contentStats: any;
}

export interface ChunkDebugInfo {
  id: string;
  content: string;
  contentLength: number;
  chunkIndex: number;
  metadata: any;
  // Table-specific fields from metadata
  contentType?: string;
  fullTableContent?: string;
  tableRowCount?: number;
  tableColumnCount?: number;
  tableHeaders?: string;
}

export interface DebugAnalysisResult {
  fileName: string;
  filePath: string;
  fileSize: number;
  availableLoaders: LoaderDebugInfo[];
  selectedLoader: LoaderDebugInfo | null;
  loadedDocuments: DocumentDebugInfo[];
  availableChunkers: ChunkerDebugInfo[];
  selectedChunker: ChunkerDebugInfo | null;
  chunks: ChunkDebugInfo[];
  processingStats: any;
  errorMessage?: string | null;
}

@Component({
  selector: 'app-document-debugger',
  standalone: false,
  templateUrl: './document-debugger.component.html',
  styleUrls: ['./document-debugger.component.css']
})
export class DocumentDebuggerComponent implements OnInit, AfterViewInit, OnDestroy {

  // ViewChild references for chunks table only (simplified)
  @ViewChild('chunksPaginator') chunksPaginator!: MatPaginator;
  @ViewChild('chunksSort') chunksSort!: MatSort;

  // Backend URL - dynamically determined
  protected backendUrl: string;

  // Data source for chunks table (simplified)
  chunksDataSource = new MatTableDataSource<ChunkDebugInfo>();

  // Table columns for simplified chunks view
  chunksColumns: string[] = ['chunkIndex', 'contentLength', 'preview'];

  // Component state
  selectedFileName = '';
  isAnalyzing = false;
  isUploading = false;

  analysisResult: DebugAnalysisResult | null = null;
  selectedFile: File | null = null;

  // Display toggles
  expandedChunkIndex: number | null = null;
  analysisError: string | null = null;

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
    private http: HttpClient,
    private ocrService: OcrService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {
    // Dynamically determine backend URL based on current location
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.backendUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
    } else {
      this.backendUrl = '/api';
    }
  }

  ngOnInit(): void {
    // Load OCR status on initialization
    this.loadOcrStatus();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  ngAfterViewInit(): void {
    this.chunksDataSource.paginator = this.chunksPaginator;
    this.chunksDataSource.sort = this.chunksSort;
  }

  // Simplified retry method
  retryAnalysis(): void {
    this.analysisError = null;
    this.analyzeFile();
  }

  // File selection handler
  onFileSelected(event: Event): void {
    const el = event.target as HTMLInputElement;
    if (el.files?.length) this.selectedFile = el.files[0];
  }

  // Upload file for analysis
  uploadTestFile(): void {
    if (!this.selectedFile) {
      this.showSnackbar('Please select a file to upload', true);
      return;
    }

    this.isUploading = true;
    const formData = new FormData();
    formData.append('file', this.selectedFile);

    this.http.post<any>(`${this.backendUrl}/documents/debug/test-upload`, formData).subscribe({
      next: (resp) => {
        this.showSnackbar('File uploaded: ' + resp.fileName);
        this.selectedFileName = resp.fileName;
        this.selectedFile = null;
        const input = document.querySelector('input[type="file"]') as HTMLInputElement;
        if (input) input.value = '';
        this.isUploading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.showSnackbar('Upload failed: ' + this.getErrorMessage(err), true);
        this.isUploading = false;
        this.cdr.detectChanges();
      }
    });
  }

  // Analyze uploaded file
  analyzeFile(): void {
    if (!this.selectedFileName) {
      this.showSnackbar('Please select a file for analysis', true);
      return;
    }

    this.isAnalyzing = true;
    this.analysisResult = null;
    this.analysisError = null;

    const params: any = { fileName: this.selectedFileName };

    this.http.post<DebugAnalysisResult>(`${this.backendUrl}/documents/debug/analyze-file`, null, { params })
      .subscribe({
        next: (result) => {
          this.analysisResult = result;
          this.updateDataSources();
          this.isAnalyzing = false;
          this.cdr.detectChanges();
          this.showSnackbar(
            result.errorMessage ? `Analysis completed with error: ${result.errorMessage}` : 'Analysis successful',
            !!result.errorMessage
          );
        },
        error: (error) => {
          this.analysisError = this.getErrorMessage(error);
          this.isAnalyzing = false;
          this.cdr.detectChanges();
          this.showSnackbar('Analysis failed: ' + this.analysisError, true);
        }
      });
  }

  private updateDataSources(): void {
    if (!this.analysisResult) return;
    // Enrich chunks with content_type from metadata
    const enrichedChunks = (this.analysisResult.chunks || []).map(chunk => {
      const contentType = chunk.metadata?.content_type || chunk.metadata?.contentType || 'text';
      const fullTableContent = chunk.metadata?.full_table_content || chunk.metadata?.fullTableContent;
      return {
        ...chunk,
        contentType,
        fullTableContent,
        tableRowCount: chunk.metadata?.table_row_count,
        tableColumnCount: chunk.metadata?.table_column_count,
        tableHeaders: chunk.metadata?.table_headers
      };
    });
    this.chunksDataSource.data = enrichedChunks;
  }

  /**
   * Check if a chunk contains table content.
   */
  isTableChunk(chunk: ChunkDebugInfo): boolean {
    return chunk.contentType === 'table';
  }

  /**
   * Get the table content for rendering (prefers full_table_content, falls back to content).
   */
  getTableContent(chunk: ChunkDebugInfo): string {
    return chunk.fullTableContent || chunk.content || '';
  }

  // Toggle chunk content expansion
  toggleChunkContent(index: number): void {
    this.expandedChunkIndex = this.expandedChunkIndex === index ? null : index;
  }

  // Get content preview with truncation
  getContentPreview(content: string | null | undefined, maxLength: number = 100): string {
    if (!content) return '';
    return content.length > maxLength ? content.substring(0, maxLength) + '...' : content;
  }

  // Copy chunk content to clipboard
  copyChunkContent(content: string): void {
    this.copyToClipboard(content);
  }

  // Copy document content to clipboard
  public copyFirstLoadedDocumentContent(): void {
    const content = this.analysisResult?.loadedDocuments?.[0]?.content;
    if (content) {
      this.copyToClipboard(content);
    } else {
      this.showSnackbar('No document content available to copy.', true);
    }
  }

  // Download document content
  public downloadFirstLoadedDocumentContent(): void {
    const firstDoc = this.analysisResult?.loadedDocuments?.[0];
    const content = firstDoc?.content;
    const baseFileName = this.analysisResult?.fileName || 'document';

    if (content) {
      const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
      this.downloadBlob(blob, `${baseFileName}_content.txt`);
      this.showSnackbar('Content downloaded successfully');
    } else {
      this.showSnackbar('No document content available to download.', true);
    }
  }

  // Helper methods
  private getErrorMessage(error: HttpErrorResponse): string {
    return error.error?.message || error.error?.error || error.message || 'Unknown server error';
  }

  private showSnackbar(message: string, isError: boolean = false, duration: number = 4000): void {
    this.snackBar.open(message, 'Close', {
      duration,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }

  private downloadBlob(blob: Blob, filename: string): void {
    if (typeof window !== 'undefined') {
      const nav = window.navigator as any;
      if (nav.msSaveBlob) {
        nav.msSaveBlob(blob, filename);
      } else {
        const link = document.createElement('a');
        const url = URL.createObjectURL(blob);
        link.href = url;
        link.download = filename;
        document.body.appendChild(link);
        link.style.display = 'none';
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
      }
    }
  }

  private copyToClipboard(text: string): void {
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text)
        .then(() => this.showSnackbar('Content copied to clipboard'))
        .catch(err => { this.fallbackCopyTextToClipboard(text); });
    } else {
      this.fallbackCopyTextToClipboard(text);
    }
  }

  private fallbackCopyTextToClipboard(text: string): void {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    try {
      if (document.execCommand('copy')) {
        this.showSnackbar('Content copied (fallback)');
      } else {
        this.showSnackbar('Fallback copy failed', true);
      }
    } catch (err) {
      this.showSnackbar('Fallback copy error', true);
    }
    document.body.removeChild(ta);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // OCR STATUS METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  loadOcrStatus(): void {
    this.isLoadingOcrStatus = true;
    this.ocrStatusError = null;
    this.cdr.detectChanges();

    // Load status, config, and models in parallel
    this.subscriptions.add(
      this.ocrService.getStatus().subscribe({
        next: (status) => {
          this.ocrStatus = status;
          this.isLoadingOcrStatus = false;
          this.cdr.detectChanges();
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
          this.cdr.detectChanges();
        }
      })
    );

    this.subscriptions.add(
      this.ocrService.getConfig().subscribe({
        next: (config) => {
          this.ocrConfig = config;
          this.cdr.detectChanges();
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
          this.cdr.detectChanges();
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
    this.cdr.detectChanges();
  }

  private getOcrErrorMessage(error: any): string {
    return error?.error?.message || error?.error?.error || error?.message || 'OCR service unavailable';
  }

  /**
   * Check if the selected file requires OCR processing.
   */
  fileRequiresOcr(): boolean {
    if (!this.selectedFile) return false;
    const ext = this.getFileExtension(this.selectedFile.name);
    return this.ocrRequiredExtensions.has(ext);
  }

  /**
   * Check if the selected file may benefit from OCR (e.g., PDFs).
   */
  fileMayBenefitFromOcr(): boolean {
    if (!this.selectedFile) return false;
    const ext = this.getFileExtension(this.selectedFile.name);
    return this.ocrBeneficialExtensions.has(ext);
  }

  /**
   * Check if the uploaded file (by name) requires OCR.
   */
  uploadedFileRequiresOcr(): boolean {
    if (!this.selectedFileName) return false;
    const ext = this.getFileExtension(this.selectedFileName);
    return this.ocrRequiredExtensions.has(ext);
  }

  /**
   * Check if the uploaded file may benefit from OCR.
   */
  uploadedFileMayBenefitFromOcr(): boolean {
    if (!this.selectedFileName) return false;
    const ext = this.getFileExtension(this.selectedFileName);
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
   * Get OCR status color class.
   */
  getOcrStatusColorClass(): string {
    if (this.isLoadingOcrStatus) return 'ocr-loading';
    if (this.ocrStatusError || !this.ocrStatus?.ocrEnabled) return 'ocr-error';
    if (!this.ocrStatus?.pipelineReady) return 'ocr-warning';
    return 'ocr-success';
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
   * Check if we should show an OCR warning for the current selection.
   */
  shouldShowOcrWarning(): boolean {
    if (this.isOcrAvailable()) return false;

    // Check selected file (before upload)
    if (this.selectedFile) {
      return this.fileRequiresOcr() || this.fileMayBenefitFromOcr();
    }

    // Check uploaded file (after upload, before analysis)
    if (this.selectedFileName) {
      return this.uploadedFileRequiresOcr() || this.uploadedFileMayBenefitFromOcr();
    }

    return false;
  }

  /**
   * Get the OCR warning message for display.
   */
  getOcrWarningMessage(): string {
    const fileName = this.selectedFile?.name || this.selectedFileName || '';
    const ext = this.getFileExtension(fileName);
    const requiresOcr = this.ocrRequiredExtensions.has(ext);

    if (requiresOcr) {
      return `Image files like "${fileName}" require OCR for text extraction. ` +
        'OCR is currently ' + (this.ocrStatus?.ocrEnabled ? 'enabled but the pipeline is not ready' : 'not available') + '.';
    }

    return 'PDF files may contain scanned images that require OCR for proper text extraction. ' +
      'OCR is currently ' + (this.ocrStatus?.ocrEnabled ? 'enabled but the pipeline is not ready' : 'not available') + '.';
  }
}
