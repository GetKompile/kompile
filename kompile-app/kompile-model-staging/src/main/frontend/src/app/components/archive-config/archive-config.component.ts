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
import { MatSnackBar } from '@angular/material/snack-bar';
import { StagingService } from '../../services/staging.service';

interface ArchiveStatus {
  initialized: boolean;
  loaded: boolean;
  archiveId: string;
  archiveVersion: string;
  totalModels: number;
  extractedModels: number;
}

interface ModelSourceConfig {
  sourceType: string;
  hasArchiveSource: boolean;
  hasRegistrySource: boolean;
  archiveOnly: boolean;
  archivePath: string;
  embeddedArchive: string;
  registryUrls: string[];
  cacheDir: string;
  verifyChecksums: boolean;
  allowFallback: boolean;
  archive: ArchiveStatus;
}

@Component({
  selector: 'app-archive-config',
  standalone: false,
  templateUrl: './archive-config.component.html',
  styleUrls: ['./archive-config.component.css']
})
export class ArchiveConfigComponent implements OnInit {

  config: ModelSourceConfig | null = null;
  loading = true;
  loadingArchive = false;
  archivePathInput = '';
  error: string | null = null;

  constructor(
    private stagingService: StagingService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadConfiguration();
  }

  loadConfiguration(): void {
    this.loading = true;
    this.error = null;

    this.stagingService.getModelSourceConfig().subscribe({
      next: (config) => {
        this.config = config;
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Failed to load configuration: ' + err.message;
        this.loading = false;
      }
    });
  }

  loadArchive(): void {
    if (!this.archivePathInput.trim()) {
      this.snackBar.open('Please enter an archive path', 'Close', { duration: 3000 });
      return;
    }

    this.loadingArchive = true;
    this.stagingService.loadArchive(this.archivePathInput.trim()).subscribe({
      next: (result: any) => {
        this.loadingArchive = false;
        if (result.success) {
          this.snackBar.open('Archive loaded successfully', 'Close', { duration: 3000 });
          this.loadConfiguration();
          this.archivePathInput = '';
        } else {
          this.snackBar.open('Failed to load archive: ' + result.error, 'Close', { duration: 5000 });
        }
      },
      error: (err) => {
        this.loadingArchive = false;
        this.snackBar.open('Failed to load archive: ' + err.message, 'Close', { duration: 5000 });
      }
    });
  }

  getSourceTypeLabel(type: string): string {
    switch (type) {
      case 'ARCHIVE': return 'Archive Only (Offline)';
      case 'REGISTRY': return 'Registry Only (Online)';
      case 'HYBRID': return 'Hybrid (Archive + Registry Fallback)';
      default: return type;
    }
  }

  getSourceTypeIcon(type: string): string {
    switch (type) {
      case 'ARCHIVE': return 'archive';
      case 'REGISTRY': return 'cloud_download';
      case 'HYBRID': return 'sync_alt';
      default: return 'settings';
    }
  }

  getStatusColor(initialized: boolean): string {
    return initialized ? 'primary' : 'warn';
  }
}
