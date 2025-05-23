/*
 * Copyright 2025 Kompile Inc.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 */

import { Component, OnInit, ViewChild, AfterViewInit, ChangeDetectorRef } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { MatTableDataSource } from '@angular/material/table';
import { MatPaginator } from '@angular/material/paginator';
import { MatSort } from '@angular/material/sort';
import { MatSnackBar } from '@angular/material/snack-bar';

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

export interface ComprehensiveChunk {
  index: number;
  id: string;
  content: string;
  contentLength: number;
  wordCount: number;
  lineCount: number;
  metadata: any;
  startPosition?: number;
  endPosition?: number;
}

export interface ComprehensiveAnalysisResult {
  fileName: string;
  fileSize: number;
  fullText: string;
  totalCharacters: number;
  totalWords: number;
  totalLines: number;
  totalParagraphs: number;
  totalChunks: number;
  chunks: ComprehensiveChunk[];
  averageChunkSize: number;
  minChunkSize: number;
  maxChunkSize: number;
  chunkingEfficiency: number;
  textCoverage: number;
  processingTimeMs: number;
}

@Component({
  selector: 'app-document-debugger',
  standalone: false,
  templateUrl: './document-debugger.component.html',
  styleUrls: ['./document-debugger.component.css']
})
export class DocumentDebuggerComponent implements OnInit, AfterViewInit {

  // ViewChild references for paginators and sorting
  @ViewChild('loadersPaginator') loadersPaginator!: MatPaginator;
  @ViewChild('loadersSort') loadersSort!: MatSort;
  @ViewChild('chunkersPaginator') chunkersPaginator!: MatPaginator;
  @ViewChild('chunkersSort') chunkersSort!: MatSort;
  @ViewChild('chunksPaginator') chunksPaginator!: MatPaginator;
  @ViewChild('chunksSort') chunksSort!: MatSort;
  @ViewChild('comprehensivePaginator') comprehensivePaginator!: MatPaginator;
  @ViewChild('comprehensiveSort') comprehensiveSort!: MatSort;

  // Backend URL
  protected backendUrl = 'http://localhost:8080/api';

  // Original data sources
  loadersDataSource = new MatTableDataSource<LoaderDebugInfo>();
  chunkersDataSource = new MatTableDataSource<ChunkerDebugInfo>();
  chunksDataSource = new MatTableDataSource<ChunkDebugInfo>();

  // Comprehensive analysis data sources
  comprehensiveResult: ComprehensiveAnalysisResult | null = null;
  comprehensiveChunks: ComprehensiveChunk[] = [];
  filteredChunksDataSource = new MatTableDataSource<ComprehensiveChunk>();
  comprehensiveChunkColumns: string[] = ['index', 'id', 'length', 'content', 'actions'];

  // Table columns
  loadersColumns: string[] = ['name', 'className', 'isNoOp', 'supportsFile', 'supportReason'];
  chunkersColumns: string[] = ['name', 'className', 'isNoOp', 'reason'];
  chunksColumns: string[] = ['chunkIndex', 'id', 'contentLength', 'preview'];

  // Component state
  selectedFileName = '';
  selectedLoaderName = '';
  selectedChunkerName = '';
  isAnalyzing = false;
  isUploading = false;

  // Analysis results
  analysisResult: DebugAnalysisResult | null = null;
  debugStatus: any = null;
  selectedFile: File | null = null;

  // Display toggles
  showRawContent = false;
  showMetadata = false;
  showFullText = false;
  showChunkMetadata = false;
  expandedChunkIndex: number | null = null;
  expandedChunks: Set<number> = new Set();

  // Filtering and search
  chunkFilter = '';
  analysisError: string | null = null;

  uploadedFiles: string[] = [];

  constructor(
    private http: HttpClient,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {
    // Initialize backend URL from environment or default
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;

      if (hostname === 'localhost' || hostname === '127.0.0.1') {
        // Development environment
        this.backendUrl = `${protocol}//${hostname}:8080/api`;
      } else {
        // Production environment - assume same origin
        this.backendUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
      }
    }
  }

  ngOnInit(): void {
    this.loadDebugStatus();
    this.loadUploadedFiles();
  }

  ngAfterViewInit(): void {
    // Original table paginators and sorters
    this.loadersDataSource.paginator = this.loadersPaginator;
    this.loadersDataSource.sort = this.loadersSort;
    this.chunkersDataSource.paginator = this.chunkersPaginator;
    this.chunkersDataSource.sort = this.chunkersSort;
    this.chunksDataSource.paginator = this.chunksPaginator;
    this.chunksDataSource.sort = this.chunksSort;

    // Comprehensive analysis paginator and sorter
    this.filteredChunksDataSource.paginator = this.comprehensivePaginator;
    this.filteredChunksDataSource.sort = this.comprehensiveSort;
  }

  loadDebugStatus(): void {
    this.http.get<any>(`${this.backendUrl}/documents/debug/status`).subscribe({
      next: (status) => {
        this.debugStatus = status;
        this.cdr.detectChanges();
      },
      error: (error) => {
        this.showSnackbar('Failed to load debug status: ' + this.getErrorMessage(error), true);
      }
    });
  }

  loadUploadedFiles(): void {
    this.http.get<any>(`${this.backendUrl}/documents/uploaded-files`).subscribe({
      next: (result) => {
        if (result.files && Array.isArray(result.files)) {
          this.uploadedFiles = result.files.map((f: any) => typeof f === 'string' ? f : f.fileName);
        }
        this.cdr.detectChanges();
      },
      error: (error) => {
        this.showSnackbar('Failed to load uploaded files: ' + this.getErrorMessage(error), true);
      }
    });
  }

  // Comprehensive Analysis Method
  performComprehensiveAnalysis(): void {
    if (!this.selectedFileName) {
      this.showSnackbar('Please select a file to analyze', true);
      return;
    }

    console.log('Starting comprehensive analysis for file:', this.selectedFileName);

    this.isAnalyzing = true;
    this.analysisError = null;
    this.comprehensiveResult = null;

    // Call backend API for comprehensive analysis
    const params = {
      fileName: this.selectedFileName,
      includeFullText: true,
      includeChunkDetails: true,
      includeMetadata: true
    };

    this.http.post<ComprehensiveAnalysisResult>(`${this.backendUrl}/documents/debug/comprehensive-analysis`, null, { params })
      .subscribe({
        next: (result) => {
          console.log('Comprehensive analysis result received:', result);
          this.comprehensiveResult = result;
          this.comprehensiveChunks = result.chunks || [];
          this.filteredChunksDataSource.data = this.comprehensiveChunks;
          this.isAnalyzing = false;
          this.cdr.detectChanges();
          this.showSnackbar('Comprehensive analysis completed successfully');
        },
        error: (error) => {
          console.error('Comprehensive analysis failed:', error);
          this.analysisError = this.getErrorMessage(error);
          this.isAnalyzing = false;
          this.cdr.detectChanges();
          this.showSnackbar('Comprehensive analysis failed: ' + this.analysisError, true);
        }
      });
  }

  // Text Display Methods
  toggleFullText(): void {
    this.showFullText = !this.showFullText;
  }

  getTextPreview(text: string, maxLength: number): string {
    if (!text) return '';
    if (text.length <= maxLength) return text;
    return text.substring(0, maxLength) + '\n\n... [Content truncated. Click "Show Full Text" to see complete content] ...';
  }

  copyFullText(): void {
    if (this.comprehensiveResult?.fullText) {
      this.copyToClipboard(this.comprehensiveResult.fullText);
      this.showSnackbar('Full text copied to clipboard');
    }
  }

  downloadFullText(): void {
    if (this.comprehensiveResult?.fullText) {
      const blob = new Blob([this.comprehensiveResult.fullText], { type: 'text/plain' });
      this.downloadBlob(blob, `${this.comprehensiveResult.fileName}_extracted_text.txt`);
      this.showSnackbar('Text file downloaded');
    }
  }

  // Chunk Management Methods
  filterChunks(): void {
    if (!this.chunkFilter.trim()) {
      this.filteredChunksDataSource.data = this.comprehensiveChunks;
    } else {
      const filtered = this.comprehensiveChunks.filter(chunk =>
        chunk.content.toLowerCase().includes(this.chunkFilter.toLowerCase()) ||
        chunk.id.toLowerCase().includes(this.chunkFilter.toLowerCase())
      );
      this.filteredChunksDataSource.data = filtered;
    }
  }

  isChunkExpanded(index: number): boolean {
    return this.expandedChunks.has(index);
  }

  expandChunk(index: number): void {
    this.expandedChunks.add(index);
  }

  collapseChunk(index: number): void {
    this.expandedChunks.delete(index);
  }

  copyChunkContent(content: string): void {
    this.copyToClipboard(content);
    this.showSnackbar('Chunk content copied to clipboard');
  }

  highlightChunkInText(chunk: ComprehensiveChunk): void {
    // Scroll to and highlight this chunk in the full text display
    this.showFullText = true;
    setTimeout(() => {
      this.showSnackbar(`Highlighting chunk ${chunk.index} in full text`);
    }, 100);
  }

  analyzeChunkSimilarity(chunk: ComprehensiveChunk): void {
    // Analyze this chunk's similarity to other chunks
    this.showSnackbar(`Analyzing similarity for chunk ${chunk.index}`);
    // TODO: Implement similarity analysis logic here
  }

  exportChunksToCSV(): void {
    if (!this.comprehensiveChunks.length) {
      this.showSnackbar('No chunks to export', true);
      return;
    }

    const csvHeaders = ['Index', 'ID', 'Content Length', 'Word Count', 'Line Count', 'Content Preview', 'Metadata'];
    const csvRows = this.comprehensiveChunks.map(chunk => [
      chunk.index.toString(),
      chunk.id,
      chunk.contentLength.toString(),
      chunk.wordCount.toString(),
      chunk.lineCount.toString(),
      `"${chunk.content.substring(0, 100).replace(/"/g, '""')}..."`,
      `"${JSON.stringify(chunk.metadata || {}).replace(/"/g, '""')}"`
    ]);

    const csvContent = [csvHeaders.join(','), ...csvRows.map(row => row.join(','))].join('\n');
    const blob = new Blob([csvContent], { type: 'text/csv' });
    this.downloadBlob(blob, `${this.comprehensiveResult?.fileName || 'document'}_chunks_analysis.csv`);
    this.showSnackbar('Chunks data exported to CSV');
  }

  retryAnalysis(): void {
    this.analysisError = null;
    this.performComprehensiveAnalysis();
  }

  // Original Analysis Methods (keeping for backward compatibility)
  onFileSelected(event: Event): void {
    const element = event.target as HTMLInputElement;
    if (element.files && element.files.length > 0) {
      this.selectedFile = element.files!![0];
    }
  }

  uploadTestFile(): void {
    if (!this.selectedFile) {
      this.showSnackbar('Please select a file to upload', true);
      return;
    }

    this.isUploading = true;
    const formData = new FormData();
    formData.append('file', this.selectedFile);

    this.http.post<any>(`${this.backendUrl}/documents/debug/test-upload`, formData).subscribe({
      next: (response) => {
        this.showSnackbar('File uploaded successfully for debugging');
        this.selectedFileName = response.fileName;
        this.loadUploadedFiles();
        this.selectedFile = null;
        // Reset file input
        const fileInput = document.getElementById('debugFileInput') as HTMLInputElement;
        if (fileInput) fileInput.value = '';
        this.isUploading = false;
        this.cdr.detectChanges();
      },
      error: (error) => {
        this.showSnackbar('Upload failed: ' + this.getErrorMessage(error), true);
        this.isUploading = false;
        this.cdr.detectChanges();
      }
    });
  }

  analyzeFile(): void {
    if (!this.selectedFileName) {
      this.showSnackbar('Please select a file to analyze', true);
      return;
    }

    console.log('Starting analysis for file:', this.selectedFileName);
    console.log('Backend URL:', this.backendUrl);

    this.isAnalyzing = true;
    this.analysisResult = null;

    const params: any = { fileName: this.selectedFileName };
    if (this.selectedLoaderName) {
      params.loaderName = this.selectedLoaderName;
    }
    if (this.selectedChunkerName) {
      params.chunkerName = this.selectedChunkerName;
    }

    console.log('Analysis params:', params);

    this.http.post<DebugAnalysisResult>(`${this.backendUrl}/documents/debug/analyze-file`, null, { params })
      .subscribe({
        next: (result) => {
          console.log('Analysis result received:', result);
          this.analysisResult = result;
          this.updateDataSources();
          this.isAnalyzing = false;
          this.cdr.detectChanges();

          if (result.errorMessage) {
            this.showSnackbar('Analysis completed with errors: ' + result.errorMessage, true);
          } else {
            this.showSnackbar('Analysis completed successfully');
          }
        },
        error: (error) => {
          console.error('Analysis failed:', error);
          console.error('Error status:', error.status);
          console.error('Error message:', error.message);
          console.error('Error body:', error.error);

          this.showSnackbar('Analysis failed: ' + this.getErrorMessage(error), true);
          this.isAnalyzing = false;
          this.cdr.detectChanges();
        }
      });
  }

  private updateDataSources(): void {
    if (!this.analysisResult) return;

    this.loadersDataSource.data = this.analysisResult.availableLoaders || [];
    this.chunkersDataSource.data = this.analysisResult.availableChunkers || [];
    this.chunksDataSource.data = this.analysisResult.chunks || [];
  }

  toggleRawContent(): void {
    this.showRawContent = !this.showRawContent;
  }

  toggleMetadata(): void {
    this.showMetadata = !this.showMetadata;
  }

  toggleChunkContent(index: number): void {
    this.expandedChunkIndex = this.expandedChunkIndex === index ? null : index;
  }

  getContentPreview(content: string, maxLength: number = 100): string {
    if (!content) return '';
    return content.length > maxLength ? content.substring(0, maxLength) + '...' : content;
  }

  getChunkContent(chunk: ChunkDebugInfo): string {
    return chunk.content || '';
  }

  getSelectedDocument(): DocumentDebugInfo | null {
    return this.analysisResult?.loadedDocuments?.[0] || null;
  }

  getRealLoadersCount(): number {
    return this.loadersDataSource.data.filter(loader => !loader.isNoOp).length;
  }

  getRealChunkersCount(): number {
    return this.chunkersDataSource.data.filter(chunker => !chunker.isNoOp).length;
  }

  getAvailableLoaderNames(): string[] {
    return this.loadersDataSource.data
      .filter(loader => !loader.isNoOp)
      .map(loader => loader.name);
  }

  getAvailableChunkerNames(): string[] {
    return this.chunkersDataSource.data
      .filter(chunker => !chunker.isNoOp)
      .map(chunker => chunker.name);
  }

  formatMetadata(metadata: any): string {
    if (!metadata) return 'No metadata available';
    try {
      return JSON.stringify(metadata, null, 2);
    } catch (error) {
      return 'Error formatting metadata: ' + String(metadata);
    }
  }

  formatContentStats(stats: any): string {
    if (!stats) return 'No stats available';

    const formattedStats: string[] = [];
    Object.keys(stats).forEach(key => {
      const value = stats[key];
      formattedStats.push(`${key}: ${value}`);
    });

    return formattedStats.join(', ');
  }

  getErrorMessage(error: HttpErrorResponse): string {
    if (error.error?.error) {
      return error.error.error;
    }
    if (error.error?.message) {
      return error.error.message;
    }
    if (error.message) {
      return error.message;
    }
    return 'Unknown error occurred';
  }

  showSnackbar(message: string, isError: boolean = false, duration: number = 4000): void {
    this.snackBar.open(message, 'Close', {
      duration,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? ['snackbar-error'] : ['snackbar-success']
    });
  }

  // Utility methods for template
  safeToString(value: any): string {
    if (value === null || value === undefined) {
      return '';
    }
    return String(value);
  }

  formatFileSize(bytes: number): string {
    if (bytes === 0) return '0 Bytes';

    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));

    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }

  getStatusColor(isNoOp: boolean, isSupported?: boolean): string {
    if (isNoOp) return 'warn';
    if (isSupported === false) return 'accent';
    return 'primary';
  }

  getStatusIcon(isNoOp: boolean, isSupported?: boolean): string {
    if (isNoOp) return 'warning';
    if (isSupported === false) return 'info';
    return 'check_circle';
  }

  hasValidMetadata(metadata: any): boolean {
    if (!metadata) return false;
    try {
      JSON.stringify(metadata);
      return Object.keys(metadata).length > 0;
    } catch (error) {
      return false;
    }
  }

  getSafeMetadata(metadata: any): any {
    if (!this.hasValidMetadata(metadata)) {
      return {};
    }
    try {
      return JSON.parse(JSON.stringify(metadata));
    } catch (error) {
      return {};
    }
  }

  getSimpleName(className: string): string {
    if (!className) return '';
    const parts = className.split('.');
    return parts.pop() || '';
  }

  // Cross-browser compatible download method
  private downloadBlob(blob: Blob, filename: string): void {
    if (typeof window !== 'undefined' && window.navigator && (window.navigator as any).msSaveBlob) {
      // IE 10+
      (window.navigator as any).msSaveBlob(blob, filename);
    } else {
      // Modern browsers
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);
    }
  }

  // Utility method for clipboard operations
  private copyToClipboard(text: string): void {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => {
        // Success handled by caller
      }).catch(() => {
        this.fallbackCopyTextToClipboard(text);
      });
    } else {
      this.fallbackCopyTextToClipboard(text);
    }
  }

  private fallbackCopyTextToClipboard(text: string): void {
    const textArea = document.createElement('textarea');
    textArea.value = text;

    // Avoid scrolling to bottom
    textArea.style.top = '0';
    textArea.style.left = '0';
    textArea.style.position = 'fixed';

    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();

    try {
      document.execCommand('copy');
    } catch (err) {
      console.error('Fallback: Unable to copy', err);
    }

    document.body.removeChild(textArea);
  }
}
