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

@Component({
  selector: 'app-document-debugger',
  standalone: false,
  templateUrl: './document-debugger.component.html',
  styleUrls: ['./document-debugger.component.css']
})
export class DocumentDebuggerComponent implements OnInit, AfterViewInit {

  // ViewChild references for chunks table only (simplified)
  @ViewChild('chunksPaginator') chunksPaginator!: MatPaginator;
  @ViewChild('chunksSort') chunksSort!: MatSort;

  // Backend URL
  protected backendUrl = 'http://localhost:8080/api';

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

  constructor(
    private http: HttpClient,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.backendUrl = (hostname === 'localhost' || hostname === '127.0.0.1')
        ? `${protocol}//${hostname}:8080/api`
        : `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
    }
  }

  ngOnInit(): void {
    // Simplified initialization - no need to load status or uploaded files
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
    this.chunksDataSource.data = this.analysisResult.chunks || [];
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
}
