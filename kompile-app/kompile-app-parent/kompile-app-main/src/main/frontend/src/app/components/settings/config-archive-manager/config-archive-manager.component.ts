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

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpEventType } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatRadioModule } from '@angular/material/radio';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';

interface ArchiveManifest {
  version: string;
  createdAt: string;
  hostname: string;
  description: string | null;
  kompileConfigs: string[];
  chatProviderConfigs: { [provider: string]: string[] };
  systemPrompts: string[];
}

interface ArchiveInfo {
  fileName: string;
  filePath: string;
  sizeBytes: number;
  lastModified: string;
  manifest: ArchiveManifest | null;
}

interface ImportResult {
  message: string;
  mode: string;
  created: string[];
  overwritten: string[];
  merged: string[];
  skipped: string[];
  totalProcessed: number;
}

@Component({
  selector: 'app-config-archive-manager',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatChipsModule,
    MatRadioModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSnackBarModule,
    MatDividerModule,
    MatTooltipModule,
    MatExpansionModule
  ],
  templateUrl: './config-archive-manager.component.html',
  styleUrls: ['./config-archive-manager.component.css']
})
export class ConfigArchiveManagerComponent implements OnInit {
  archives: ArchiveInfo[] = [];
  isLoading = false;
  isExporting = false;
  isImporting = false;
  exportDescription = '';
  importMode: 'append' | 'override' = 'append';
  selectedFile: File | null = null;
  previewManifest: ArchiveManifest | null = null;
  lastImportResult: ImportResult | null = null;

  constructor(
    private http: HttpClient,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadArchives();
  }

  loadArchives(): void {
    this.isLoading = true;
    this.http.get<{ archives: ArchiveInfo[]; total: number }>('/api/config-archives')
      .subscribe({
        next: (res) => {
          this.archives = res.archives;
          this.isLoading = false;
        },
        error: (err) => {
          this.snackBar.open('Failed to load archives', 'Dismiss', { duration: 3000 });
          this.isLoading = false;
        }
      });
  }

  exportAndSave(): void {
    this.isExporting = true;
    const params: any = {};
    if (this.exportDescription.trim()) {
      params.description = this.exportDescription.trim();
    }
    this.http.post<{ message: string; fileName: string }>('/api/config-archives/export/save', null, { params })
      .subscribe({
        next: (res) => {
          this.snackBar.open('Archive saved: ' + res.fileName, 'Dismiss', { duration: 3000 });
          this.exportDescription = '';
          this.isExporting = false;
          this.loadArchives();
        },
        error: (err) => {
          this.snackBar.open('Export failed: ' + (err.error?.error || err.message), 'Dismiss', { duration: 5000 });
          this.isExporting = false;
        }
      });
  }

  exportAndDownload(): void {
    this.isExporting = true;
    const params: any = {};
    if (this.exportDescription.trim()) {
      params.description = this.exportDescription.trim();
    }
    this.http.post('/api/config-archives/export', null, { params, responseType: 'blob' })
      .subscribe({
        next: (blob) => {
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = 'kompile-config-' + new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19) + '.zip';
          a.click();
          window.URL.revokeObjectURL(url);
          this.isExporting = false;
        },
        error: (err) => {
          this.snackBar.open('Export failed', 'Dismiss', { duration: 3000 });
          this.isExporting = false;
        }
      });
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.selectedFile = input.files[0];
      this.previewManifest = null;
      this.lastImportResult = null;
      this.previewFile();
    }
  }

  previewFile(): void {
    if (!this.selectedFile) return;
    const formData = new FormData();
    formData.append('file', this.selectedFile);
    this.http.post<{ manifest: ArchiveManifest }>('/api/config-archives/preview', formData)
      .subscribe({
        next: (res) => {
          this.previewManifest = res.manifest;
        },
        error: (err) => {
          this.snackBar.open('Invalid archive file', 'Dismiss', { duration: 3000 });
        }
      });
  }

  importFile(): void {
    if (!this.selectedFile) return;
    this.isImporting = true;
    const formData = new FormData();
    formData.append('file', this.selectedFile);
    this.http.post<ImportResult>('/api/config-archives/import', formData, {
      params: { mode: this.importMode }
    }).subscribe({
      next: (res) => {
        this.lastImportResult = res;
        this.snackBar.open(
          `Import complete: ${res.totalProcessed} files processed`,
          'Dismiss', { duration: 5000 }
        );
        this.isImporting = false;
      },
      error: (err) => {
        this.snackBar.open('Import failed: ' + (err.error?.error || err.message), 'Dismiss', { duration: 5000 });
        this.isImporting = false;
      }
    });
  }

  importSaved(archive: ArchiveInfo): void {
    this.isImporting = true;
    this.http.post<ImportResult>(
      `/api/config-archives/import/${archive.fileName}`,
      null,
      { params: { mode: this.importMode } }
    ).subscribe({
      next: (res) => {
        this.lastImportResult = res;
        this.snackBar.open(
          `Imported "${archive.fileName}": ${res.totalProcessed} files`,
          'Dismiss', { duration: 5000 }
        );
        this.isImporting = false;
      },
      error: (err) => {
        this.snackBar.open('Import failed: ' + (err.error?.error || err.message), 'Dismiss', { duration: 5000 });
        this.isImporting = false;
      }
    });
  }

  downloadArchive(archive: ArchiveInfo): void {
    this.http.get(`/api/config-archives/${archive.fileName}/download`, { responseType: 'blob' })
      .subscribe({
        next: (blob) => {
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = archive.fileName;
          a.click();
          window.URL.revokeObjectURL(url);
        },
        error: () => {
          this.snackBar.open('Download failed', 'Dismiss', { duration: 3000 });
        }
      });
  }

  deleteArchive(archive: ArchiveInfo): void {
    if (!confirm(`Delete archive "${archive.fileName}"?`)) return;
    this.http.delete<{ message: string }>(`/api/config-archives/${archive.fileName}`)
      .subscribe({
        next: () => {
          this.snackBar.open('Archive deleted', 'Dismiss', { duration: 3000 });
          this.loadArchives();
        },
        error: () => {
          this.snackBar.open('Delete failed', 'Dismiss', { duration: 3000 });
        }
      });
  }

  clearImportSelection(): void {
    this.selectedFile = null;
    this.previewManifest = null;
    this.lastImportResult = null;
  }

  formatSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  formatDate(iso: string): string {
    try {
      return new Date(iso).toLocaleString();
    } catch {
      return iso;
    }
  }

  getProviderNames(manifest: ArchiveManifest): string[] {
    return Object.keys(manifest.chatProviderConfigs || {});
  }

  getProviderIcon(provider: string): string {
    const icons: { [key: string]: string } = {
      claude: 'smart_toy',
      codex: 'code',
      qwen: 'translate',
      opencode: 'terminal',
      gemini: 'auto_awesome'
    };
    return icons[provider] || 'settings';
  }

  getTotalFileCount(manifest: ArchiveManifest): number {
    let total = (manifest.kompileConfigs?.length || 0) + (manifest.systemPrompts?.length || 0);
    for (const files of Object.values(manifest.chatProviderConfigs || {})) {
      total += files.length;
    }
    return total;
  }
}
