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

import { Component, Inject, OnInit, OnDestroy, SecurityContext } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { SourceViewerService } from '../../services/source-viewer.service';
import {
  SourceInfo,
  TextContentResponse,
  SourceViewMode,
  formatFileSize,
  getSourceViewModeIcon
} from '../../models/api-models';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

export interface SourceViewerDialogData {
  /** File name to view */
  fileName: string;
  /** Optional checksum for stored documents */
  checksum?: string | null;
  /** Optional source info if already available */
  sourceInfo?: SourceInfo;
}

@Component({
  selector: 'app-source-viewer-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatExpansionModule,
    MatTooltipModule,
    // DatePipe is included in CommonModule
  ],
  templateUrl: './source-viewer-dialog.component.html',
  styleUrls: ['./source-viewer-dialog.component.css']
})
export class SourceViewerDialogComponent implements OnInit, OnDestroy {

  // State
  isLoading = true;
  error: string | null = null;
  sourceInfo: SourceInfo | null = null;
  textContent: string | null = null;
  textTruncated = false;
  lineCount = 0;
  viewMode: SourceViewMode = 'DOWNLOAD_ONLY';

  // For embedded/image content
  contentUrl: SafeResourceUrl | null = null;
  rawContentUrl: string | null = null;

  // Syntax highlighting
  syntaxLanguage: string = 'text';

  // UI helpers
  formatFileSize = formatFileSize;
  getViewModeIcon = getSourceViewModeIcon;

  private destroy$ = new Subject<void>();

  constructor(
    public dialogRef: MatDialogRef<SourceViewerDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: SourceViewerDialogData,
    private sourceViewerService: SourceViewerService,
    private sanitizer: DomSanitizer
  ) { }

  ngOnInit(): void {
    this.loadContent();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadContent(): void {
    this.isLoading = true;
    this.error = null;

    // First get source info if not provided
    if (this.data.sourceInfo) {
      this.sourceInfo = this.data.sourceInfo;
      this.setupViewMode();
    } else {
      this.sourceViewerService.getSourceInfo(this.data.fileName)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (info) => {
            this.sourceInfo = info;
            this.setupViewMode();
          },
          error: (err) => {
            this.error = 'Failed to load source info: ' + err.message;
            this.isLoading = false;
          }
        });
    }
  }

  private setupViewMode(): void {
    if (!this.sourceInfo) return;

    this.viewMode = this.sourceInfo.viewMode as SourceViewMode;
    this.syntaxLanguage = this.sourceViewerService.getSyntaxLanguage(this.sourceInfo.extension);

    switch (this.viewMode) {
      case 'TEXT':
        this.loadTextContent();
        break;
      case 'IMAGE':
        this.setupImageUrl();
        break;
      case 'EMBEDDED':
        this.setupEmbeddedUrl();
        break;
      default:
        this.isLoading = false;
    }
  }

  private loadTextContent(): void {
    this.sourceViewerService.getTextContent(this.data.fileName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response: TextContentResponse) => {
          if (response.error) {
            this.error = response.error;
          } else {
            this.textContent = response.content;
            this.textTruncated = response.truncated;
            this.lineCount = response.lineCount;
          }
          this.isLoading = false;
        },
        error: (err) => {
          this.error = 'Failed to load text content: ' + err.message;
          this.isLoading = false;
        }
      });
  }

  private setupImageUrl(): void {
    const url = this.data.checksum
      ? this.sourceViewerService.getFileUrlByChecksum(this.data.checksum, false)
      : this.sourceViewerService.getFileUrl(this.data.fileName, false);

    this.rawContentUrl = url;
    this.contentUrl = this.sanitizer.bypassSecurityTrustResourceUrl(url);
    this.isLoading = false;
  }

  private setupEmbeddedUrl(): void {
    const url = this.data.checksum
      ? this.sourceViewerService.getFileUrlByChecksum(this.data.checksum, false)
      : this.sourceViewerService.getFileUrl(this.data.fileName, false);

    this.rawContentUrl = url;
    this.contentUrl = this.sanitizer.bypassSecurityTrustResourceUrl(url);
    this.isLoading = false;
  }

  onDownload(): void {
    if (this.data.checksum) {
      this.sourceViewerService.downloadFileByChecksum(this.data.checksum);
    } else {
      this.sourceViewerService.downloadFile(this.data.fileName);
    }
  }

  onClose(): void {
    this.dialogRef.close();
  }

  getFileExtension(): string {
    if (this.sourceInfo) {
      return this.sourceInfo.extension.toUpperCase();
    }
    const parts = this.data.fileName.split('.');
    return parts.length > 1 ? parts[parts.length - 1].toUpperCase() : 'FILE';
  }

  getViewModeLabel(): string {
    switch (this.viewMode) {
      case 'TEXT': return 'Text Viewer';
      case 'IMAGE': return 'Image Viewer';
      case 'EMBEDDED': return 'Document Viewer';
      default: return 'File Info';
    }
  }

  copyToClipboard(): void {
    if (this.textContent) {
      navigator.clipboard.writeText(this.textContent).then(() => {
        // Could show a snackbar notification here
        console.log('Content copied to clipboard');
      }).catch(err => {
        console.error('Failed to copy to clipboard:', err);
      });
    }
  }

  openInNewTab(): void {
    if (this.rawContentUrl) {
      window.open(this.rawContentUrl, '_blank');
    }
  }
}
