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

import { Component, OnInit, OnDestroy, Inject, Optional } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSelectModule } from '@angular/material/select';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

import {
  Nd4jEnvironmentService
} from '../../services/nd4j-environment.service';

interface FrameworkSettings {
  optimizerEnabled: boolean;
  optimizerFp16: boolean;
  dspNoFreeze: boolean;
  dspNoNativeDecode: boolean;
  dspNoAttnOverride: boolean;
  dspNoDirect: boolean;
  tritonSkipKernels: boolean;
  tritonTf32: boolean;
  cublasDisableWorkspace: boolean;
  dspDiagnostics: string;
  opTiming: boolean;
}

interface SubsystemInfo {
  execution?: { dspEnabled?: boolean; summary?: string; error?: string };
  dsp?: { enabledCategories?: string[]; planReport?: string; error?: string };
  memory?: { heapUsedBytes?: number; heapMaxBytes?: number; gcFrequency?: number; managerType?: string; error?: string };
  profiling?: { enabled?: boolean; frequency?: number; error?: string };
  lifecycle?: { totalCreated?: number; totalDestroyed?: number; liveCount?: number; error?: string };
  device?: { count?: number; hasGpu?: boolean; multiDevice?: boolean; summaries?: string[]; error?: string };
  workspaces?: { defaultSize?: number; initialSize?: number; learningEnabled?: boolean; debugMode?: string; error?: string };
  constants?: { cachedMb?: number; tadCacheEntries?: number; error?: string };
  error?: string;
}

@Component({
  selector: 'app-nd4j-framework',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatExpansionModule,
    MatDividerModule,
    MatChipsModule,
    MatDialogModule,
    MatInputModule,
    MatFormFieldModule,
    MatSnackBarModule,
    MatSlideToggleModule,
    MatSelectModule
  ],
  templateUrl: './nd4j-framework.component.html',
  styleUrls: ['./nd4j-framework.component.css']
})
export class Nd4jFrameworkComponent implements OnInit, OnDestroy {
  loading = false;
  error: string | null = null;
  isModal = false;
  isCudaBackend = false;

  // Framework settings
  settings: FrameworkSettings = {
    optimizerEnabled: true,
    optimizerFp16: true,
    dspNoFreeze: false,
    dspNoNativeDecode: false,
    dspNoAttnOverride: false,
    dspNoDirect: false,
    tritonSkipKernels: false,
    tritonTf32: false,
    cublasDisableWorkspace: false,
    dspDiagnostics: '',
    opTiming: false
  };

  // Subsystem status from Nd4j.framework
  subsystems: SubsystemInfo = {};

  // Descriptions from backend
  descriptions: { [key: string]: string } = {};

  private destroy$ = new Subject<void>();

  constructor(
    private nd4jService: Nd4jEnvironmentService,
    private snackBar: MatSnackBar,
    @Optional() private dialogRef: MatDialogRef<Nd4jFrameworkComponent>,
    @Optional() @Inject(MAT_DIALOG_DATA) public data: any
  ) {
    this.isModal = !!dialogRef;
  }

  ngOnInit(): void {
    this.loadFrameworkSettings();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadFrameworkSettings(): void {
    this.loading = true;
    this.error = null;

    this.nd4jService.getFrameworkSettings()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.settings = {
            optimizerEnabled: response.optimizerEnabled ?? true,
            optimizerFp16: response.optimizerFp16 ?? true,
            dspNoFreeze: response.dspNoFreeze ?? false,
            dspNoNativeDecode: response.dspNoNativeDecode ?? false,
            dspNoAttnOverride: response.dspNoAttnOverride ?? false,
            dspNoDirect: response.dspNoDirect ?? false,
            tritonSkipKernels: response.tritonSkipKernels ?? false,
            tritonTf32: response.tritonTf32 ?? false,
            cublasDisableWorkspace: response.cublasDisableWorkspace ?? false,
            dspDiagnostics: response.dspDiagnostics || '',
            opTiming: response.opTiming ?? false
          };
          this.descriptions = response.description || {};
          this.isCudaBackend = response.isCudaBackend ?? false;
          this.subsystems = response.subsystems || {};
          this.loading = false;
        },
        error: (err) => {
          this.error = err?.message || 'Failed to load framework settings';
          this.loading = false;
        }
      });
  }

  close(): void {
    if (this.dialogRef) {
      this.dialogRef.close();
    }
  }

  updateSetting(field: keyof FrameworkSettings, value: any): void {
    this.loading = true;
    const settings: any = { [field]: value };

    this.nd4jService.updateFrameworkSettings(settings)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          const settingName = this.formatSettingName(field);
          this.snackBar.open(`${settingName} updated`, 'Close', { duration: 2000 });
          this.loading = false;
        },
        error: (err) => {
          this.snackBar.open(`Failed to update ${field}: ${err.message}`, 'Close', { duration: 3000 });
          this.loading = false;
        }
      });
  }

  updateDspDiagnostics(): void {
    this.loading = true;
    this.nd4jService.updateFrameworkSettings({ dspDiagnostics: this.settings.dspDiagnostics })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('DSP diagnostics updated', 'Close', { duration: 2000 });
          this.loading = false;
        },
        error: (err) => {
          this.snackBar.open(`Failed to update DSP diagnostics: ${err.message}`, 'Close', { duration: 3000 });
          this.loading = false;
        }
      });
  }

  applyPreset(presetName: string): void {
    this.loading = true;
    this.nd4jService.applyFrameworkPreset(presetName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`Preset '${presetName}' applied successfully`, 'Close', { duration: 3000 });
          this.loadFrameworkSettings();
        },
        error: (err) => {
          this.snackBar.open(`Failed to apply preset: ${err.message}`, 'Close', { duration: 5000 });
          this.loading = false;
        }
      });
  }

  formatSettingName(field: string): string {
    return field.replace(/([A-Z])/g, ' $1')
      .replace(/^./, str => str.toUpperCase());
  }

  getDescription(field: string): string {
    return this.descriptions[field] || '';
  }

  formatBytes(bytes: number | undefined): string {
    if (bytes === undefined || bytes === null) return 'N/A';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }

  getHeapPercent(): number {
    const mem = this.subsystems.memory;
    if (!mem?.heapUsedBytes || !mem?.heapMaxBytes || mem.heapMaxBytes === 0) return 0;
    return Math.round((mem.heapUsedBytes / mem.heapMaxBytes) * 100);
  }
}
