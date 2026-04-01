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
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatBadgeModule } from '@angular/material/badge';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import {
  SdkService,
  SdkEntry,
  SdkArtifact,
  SdzModel,
  SdkPlatformsResponse,
  ScaffoldInfo,
  SdkStatus
} from '../../services/sdk.service';

@Component({
  selector: 'app-sdk-hub',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    MatInputModule,
    MatTabsModule,
    MatTableModule,
    MatChipsModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatDividerModule,
    MatExpansionModule,
    MatBadgeModule,
    MatSlideToggleModule
  ],
  templateUrl: './sdk-hub.component.html',
  styleUrls: ['./sdk-hub.component.css']
})
export class SdkHubComponent implements OnInit {
  // Status
  status: SdkStatus | null = null;
  loading = false;

  // SDK list
  sdks: SdkEntry[] = [];
  models: SdzModel[] = [];
  platforms: SdkPlatformsResponse | null = null;

  // Filters
  platformFilter = '';
  platformCategoryFilter = 'all';

  // Download state
  downloadingSdk: string | null = null;
  downloadingModel: string | null = null;

  // Scaffold state
  scaffoldPlatform = 'ios';
  scaffoldInfo: ScaffoldInfo | null = null;
  scaffoldProjectName = 'KompileChat';
  scaffoldPackageName = 'ai.kompile.chat';
  scaffoldModel = 'smollm-135m';
  scaffoldMode = 'local';
  scaffoldIncludeModel = true;
  scaffoldIncludeSdk = false;
  scaffoldGenerating = false;
  scaffoldError: string | null = null;

  constructor(
    private sdkService: SdkService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.loading = true;
    this.sdkService.getStatus().subscribe({
      next: (status) => {
        this.status = status;
        this.loading = false;
      },
      error: () => this.loading = false
    });
    this.sdkService.getPlatforms().subscribe({
      next: (platforms) => this.platforms = platforms
    });
    this.sdkService.listSdks().subscribe({
      next: (result) => {
        this.sdks = result.sdks || [];
        this.models = result.models || [];
      }
    });
    this.loadScaffoldInfo();
  }

  loadScaffoldInfo(): void {
    this.sdkService.getScaffoldInfo(this.scaffoldPlatform).subscribe({
      next: (info) => this.scaffoldInfo = info
    });
  }

  onScaffoldPlatformChange(): void {
    this.loadScaffoldInfo();
  }

  // ═══════════════════════════════════════════════════════════════
  // SDK ARTIFACTS
  // ═══════════════════════════════════════════════════════════════

  getFilteredArtifacts(sdk: SdkEntry): SdkArtifact[] {
    let artifacts = sdk.artifacts;

    if (this.platformCategoryFilter !== 'all') {
      const categoryPlatforms = this.getCategoryPlatforms(this.platformCategoryFilter);
      artifacts = artifacts.filter(a =>
        categoryPlatforms.some(p => a.platform.startsWith(p))
      );
    }

    if (this.platformFilter) {
      const filter = this.platformFilter.toLowerCase();
      artifacts = artifacts.filter(a =>
        a.platform.toLowerCase().includes(filter)
      );
    }

    return artifacts;
  }

  private getCategoryPlatforms(category: string): string[] {
    if (!this.platforms) return [];
    switch (category) {
      case 'ios': return this.platforms.iosPlatforms;
      case 'android': return this.platforms.androidPlatforms;
      case 'desktop': return this.platforms.desktopPlatforms;
      case 'mobile': return this.platforms.mobilePlatforms;
      default: return this.platforms.basePlatforms;
    }
  }

  downloadSdkArtifact(platform: string): void {
    this.downloadingSdk = platform;
    // Split platform into base + chip if needed
    const parts = this.splitClassifier(platform);
    this.sdkService.downloadSdk(parts.base, parts.chip).subscribe({
      next: (result) => {
        this.downloadingSdk = null;
        this.snackBar.open(`SDK downloaded: ${result.fileName}`, 'OK', { duration: 3000 });
        this.loadData();
      },
      error: (err) => {
        this.downloadingSdk = null;
        this.snackBar.open(`Download failed: ${err.error?.error || err.message}`, 'Dismiss', { duration: 5000 });
      }
    });
  }

  private splitClassifier(classifier: string): { base: string; chip?: string } {
    // Known base platforms that may have suffixes
    const basePlatforms = this.platforms?.basePlatforms || [];
    for (const base of basePlatforms) {
      if (classifier.startsWith(base + '-') && classifier !== base) {
        return { base, chip: classifier.substring(base.length + 1) };
      }
      if (classifier === base) {
        return { base };
      }
    }
    return { base: classifier };
  }

  // ═══════════════════════════════════════════════════════════════
  // MODEL BUNDLES
  // ═══════════════════════════════════════════════════════════════

  downloadModelBundle(modelId: string): void {
    this.downloadingModel = modelId;
    this.sdkService.downloadModel(modelId).subscribe({
      next: (result) => {
        this.downloadingModel = null;
        this.snackBar.open(`Model downloaded: ${result.fileName}`, 'OK', { duration: 3000 });
        this.loadData();
      },
      error: (err) => {
        this.downloadingModel = null;
        this.snackBar.open(`Download failed: ${err.error?.error || err.message}`, 'Dismiss', { duration: 5000 });
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════
  // SCAFFOLD
  // ═══════════════════════════════════════════════════════════════

  getScaffoldCommand(): string {
    return `kompile sdk scaffold \\
  --model ${this.scaffoldModel} \\
  --platform ${this.scaffoldPlatform} \\
  --project-name ${this.scaffoldProjectName} \\
  --package-name ${this.scaffoldPackageName} \\
  --mode ${this.scaffoldMode}`;
  }

  copyScaffoldCommand(): void {
    navigator.clipboard.writeText(this.getScaffoldCommand().replace(/\\\n\s*/g, ' ')).then(() => {
      this.snackBar.open('Command copied to clipboard', 'OK', { duration: 2000 });
    });
  }

  generateAndDownload(): void {
    this.scaffoldGenerating = true;
    this.scaffoldError = null;

    this.sdkService.scaffoldProject({
      platform: this.scaffoldPlatform,
      projectName: this.scaffoldProjectName,
      packageName: this.scaffoldPackageName,
      modelId: this.scaffoldModel,
      inferenceMode: this.scaffoldMode,
      includeModel: this.scaffoldIncludeModel,
      includeSdk: this.scaffoldIncludeSdk
    }).subscribe({
      next: (blob) => {
        this.scaffoldGenerating = false;
        // Trigger browser download
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = this.scaffoldProjectName + '.zip';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        this.snackBar.open('Project generated and downloaded!', 'OK', { duration: 3000 });
      },
      error: (err) => {
        this.scaffoldGenerating = false;
        this.scaffoldError = err.error?.error || err.message || 'Generation failed';
        this.snackBar.open('Scaffold generation failed', 'Dismiss', { duration: 5000 });
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════
  // HELPERS
  // ═══════════════════════════════════════════════════════════════

  getPackagingIcon(packaging: string): string {
    switch (packaging) {
      case 'xcframework': return 'phone_iphone';
      case 'aar': return 'android';
      case 'zip': return 'computer';
      default: return 'inventory_2';
    }
  }

  getPackagingColor(packaging: string): string {
    switch (packaging) {
      case 'xcframework': return '#007AFF';
      case 'aar': return '#3DDC84';
      case 'zip': return '#6B7280';
      default: return '#9CA3AF';
    }
  }

  getPlatformCategory(platform: string): string {
    if (platform.startsWith('ios')) return 'iOS';
    if (platform.startsWith('android')) return 'Android';
    if (platform.startsWith('linux')) return 'Linux';
    if (platform.startsWith('macosx')) return 'macOS';
    if (platform.startsWith('windows')) return 'Windows';
    return 'Other';
  }
}
