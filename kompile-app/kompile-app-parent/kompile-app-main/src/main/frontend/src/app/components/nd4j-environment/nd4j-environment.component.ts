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
import { MatSliderModule } from '@angular/material/slider';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Subject } from 'rxjs';
import { takeUntil, debounceTime } from 'rxjs/operators';

import {
  Nd4jEnvironmentService,
  Nd4jConfigResponse,
  PresetsResponse
} from '../../services/nd4j-environment.service';

@Component({
  selector: 'app-nd4j-environment',
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
    MatSliderModule,
    MatSlideToggleModule,
    MatSelectModule,
    MatTableModule,
    MatProgressBarModule
  ],
  templateUrl: './nd4j-environment.component.html',
  styleUrls: ['./nd4j-environment.component.css']
})
export class Nd4jEnvironmentComponent implements OnInit, OnDestroy {
  config: Nd4jConfigResponse | null = null;
  presets: PresetsResponse | null = null;
  loading = false;
  error: string | null = null;
  isModal = false;

  // Edit state - Threading
  maxThreads: number = 4;
  maxMasterThreads: number = 4;
  ompNumThreads: number = 4;
  openBlasThreads: number = 1;
  lifecycleTracking: boolean = false;
  selectedPreset: string = '';
  availableProcessors: number = 1;

  // Edit state - Memory (in MB, 0 = unlimited)
  maxPrimaryMemoryMb: number = 0;
  maxSpecialMemoryMb: number = 0;
  maxDeviceMemoryMb: number = 0;

  // Edit state - Performance thresholds
  tadThreshold: number = 0;
  elementwiseThreshold: number = 0;

  // Edit state - Debug settings
  debugEnabled: boolean = false;
  verboseEnabled: boolean = false;
  profilingEnabled: boolean = false;
  leaksDetectorEnabled: boolean = false;

  // Edit state - Advanced debug settings
  logNativeNDArrayCreation: boolean = false;
  logNDArrayEvents: boolean = false;
  checkInputChange: boolean = false;
  checkOutputChange: boolean = false;
  trackWorkspaceOpenClose: boolean = false;
  variableTracingEnabled: boolean = false;

  // Edit state - Function trace settings
  funcTracePrintAllocate: boolean = false;
  funcTracePrintDeallocate: boolean = false;
  funcTracePrintJavaOnly: boolean = false;

  // Edit state - Deletion settings
  deleteShapeInfo: boolean = true;
  deletePrimary: boolean = true;
  deleteSpecial: boolean = true;

  // Edit state - Core settings
  enableBlas: boolean = true;
  helpersAllowed: boolean = true;

  // Edit state - JavaCPP settings
  javacppLoggerDebug: boolean = false;
  javacppPathsFirst: boolean = true;

  // Edit state - BLAS serialization
  blasSerializationEnabled: boolean = true;

  // System info for memory reference
  systemMaxMemoryMb: number = 0;

  // Device information
  isCudaBackend: boolean = false;
  cudaDeviceCount: number = 0;
  cudaCurrentDevice: number | null = null;
  cudaTensorCoreEnabled: boolean | null = null;

  // Triton compiler settings
  tritonBuildThreads: number = 1;
  tritonCacheEnabled: boolean = true;
  tritonVerbose: boolean = false;
  tritonAlwaysCompile: boolean = false;
  tritonNumWarps: number = 0;
  tritonNumStages: number = 0;
  tritonNumCTAs: number = 1;
  tritonEnableFpFusion: boolean = true;
  tritonCacheDir: string = '';
  tritonDumpDir: string = '';
  tritonOverrideArch: string = '';

  private destroy$ = new Subject<void>();

  // Debounce subjects for slider changes
  private maxThreadsChange$ = new Subject<number>();
  private maxMasterThreadsChange$ = new Subject<number>();
  private ompThreadsChange$ = new Subject<number>();
  private openBlasThreadsChange$ = new Subject<number>();
  private maxPrimaryMemoryChange$ = new Subject<number>();
  private maxSpecialMemoryChange$ = new Subject<number>();
  private maxDeviceMemoryChange$ = new Subject<number>();

  constructor(
    private nd4jService: Nd4jEnvironmentService,
    private snackBar: MatSnackBar,
    @Optional() private dialogRef: MatDialogRef<Nd4jEnvironmentComponent>,
    @Optional() @Inject(MAT_DIALOG_DATA) public data: any
  ) {
    this.isModal = !!dialogRef;
  }

  ngOnInit(): void {
    this.loadConfiguration();
    this.loadPresets();
    this.setupDebouncedUpdates();
  }

  private setupDebouncedUpdates(): void {
    // Debounce thread setting changes to avoid spamming the server
    this.maxThreadsChange$.pipe(
      debounceTime(500),
      takeUntil(this.destroy$)
    ).subscribe(value => this.applyMaxThreads(value));

    this.maxMasterThreadsChange$.pipe(
      debounceTime(500),
      takeUntil(this.destroy$)
    ).subscribe(value => this.applyMaxMasterThreads(value));

    this.ompThreadsChange$.pipe(
      debounceTime(500),
      takeUntil(this.destroy$)
    ).subscribe(value => this.applyOmpThreads(value));

    this.openBlasThreadsChange$.pipe(
      debounceTime(500),
      takeUntil(this.destroy$)
    ).subscribe(value => this.applyOpenBlasThreads(value));

    // Debounce memory setting changes
    this.maxPrimaryMemoryChange$.pipe(
      debounceTime(500),
      takeUntil(this.destroy$)
    ).subscribe(value => this.applyMaxPrimaryMemory(value));

    this.maxSpecialMemoryChange$.pipe(
      debounceTime(500),
      takeUntil(this.destroy$)
    ).subscribe(value => this.applyMaxSpecialMemory(value));

    this.maxDeviceMemoryChange$.pipe(
      debounceTime(500),
      takeUntil(this.destroy$)
    ).subscribe(value => this.applyMaxDeviceMemory(value));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadConfiguration(): void {
    this.loading = true;
    this.error = null;

    this.nd4jService.getConfiguration()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          this.config = config;
          this.updateEditState();
          this.loading = false;
        },
        error: (err) => {
          this.error = err?.message || 'Failed to load ND4J configuration';
          this.loading = false;
        }
      });
  }

  loadPresets(): void {
    this.nd4jService.getPresets()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (presets) => {
          this.presets = presets;
        },
        error: (err) => {
          console.error('Failed to load presets:', err);
        }
      });
  }

  updateEditState(): void {
    if (this.config?.actual) {
      // Threading
      this.maxThreads = this.config.actual.maxThreads || 4;
      this.maxMasterThreads = this.config.actual.maxMasterThreads || 4;
      this.ompNumThreads = this.config.summary?.blas?.ompNumThreads || 4;
      this.openBlasThreads = this.config.actual.openBlasThreads || 1;
      this.lifecycleTracking = this.config.actual.lifecycleTracking || false;
      this.availableProcessors = this.config.summary?.threads?.availableProcessors || 1;

      // Memory (convert bytes to MB for display, 0 = unlimited)
      this.maxPrimaryMemoryMb = this.bytesToMb(this.config.actual.maxPrimaryMemory || 0);
      this.maxSpecialMemoryMb = this.bytesToMb(this.config.actual.maxSpecialMemory || 0);
      this.maxDeviceMemoryMb = this.bytesToMb(this.config.actual.maxDeviceMemory || 0);

      // Performance thresholds
      this.tadThreshold = this.config.actual.tadThreshold || 0;
      this.elementwiseThreshold = this.config.actual.elementwiseThreshold || 0;

      // Debug settings
      this.debugEnabled = this.config.actual.debug || false;
      this.verboseEnabled = this.config.actual.verbose || false;
      this.profilingEnabled = this.config.actual.profiling || false;
      this.leaksDetectorEnabled = this.config.actual.leaksDetector || false;

      // Advanced debug settings
      this.logNativeNDArrayCreation = this.config.actual.logNativeNDArrayCreation || false;
      this.logNDArrayEvents = this.config.actual.logNDArrayEvents || false;
      this.checkInputChange = this.config.actual.checkInputChange || false;
      this.checkOutputChange = this.config.actual.checkOutputChange || false;
      this.trackWorkspaceOpenClose = this.config.actual.trackWorkspaceOpenClose || false;
      this.variableTracingEnabled = this.config.actual.variableTracingEnabled || false;

      // Function trace settings
      this.funcTracePrintAllocate = this.config.actual.funcTracePrintAllocate || false;
      this.funcTracePrintDeallocate = this.config.actual.funcTracePrintDeallocate || false;
      this.funcTracePrintJavaOnly = this.config.actual.funcTracePrintJavaOnly || false;

      // Deletion settings
      this.deleteShapeInfo = this.config.actual.deleteShapeInfo !== false;
      this.deletePrimary = this.config.actual.deletePrimary !== false;
      this.deleteSpecial = this.config.actual.deleteSpecial !== false;

      // Core settings
      this.enableBlas = this.config.actual.enableBlas !== false;
      this.helpersAllowed = this.config.actual.helpersAllowed !== false;

      // JavaCPP settings
      this.javacppLoggerDebug = this.config.actual.javacppLoggerDebug || false;
      this.javacppPathsFirst = this.config.actual.javacppPathsFirst !== false;

      // BLAS serialization
      this.blasSerializationEnabled = this.config.actual.blasSerializationEnabled !== false;

      // System max memory for reference (estimate from available processors)
      this.systemMaxMemoryMb = this.availableProcessors * 1024; // Rough estimate

      // Device information
      this.isCudaBackend = this.config.summary?.isCudaBackend || false;
      if (this.isCudaBackend && this.config.summary?.cuda) {
        this.cudaDeviceCount = this.config.summary.cuda.deviceCount || 0;
        this.cudaCurrentDevice = this.config.summary.cuda.currentDevice ?? null;
        this.cudaTensorCoreEnabled = this.config.summary.cuda.tensorCoreEnabled ?? null;
      } else {
        this.cudaDeviceCount = 0;
        this.cudaCurrentDevice = null;
        this.cudaTensorCoreEnabled = null;
      }

      // Triton settings
      if (this.isCudaBackend && this.config.summary?.triton) {
        this.tritonBuildThreads = this.config.summary.triton.buildThreads || 1;
        this.tritonCacheEnabled = this.config.summary.triton.cacheEnabled !== false;
        this.tritonVerbose = this.config.summary.triton.verbose || false;
        this.tritonAlwaysCompile = this.config.summary.triton.alwaysCompile || false;
        this.tritonNumWarps = this.config.summary.triton.numWarps || 0;
        this.tritonNumStages = this.config.summary.triton.numStages || 0;
        this.tritonNumCTAs = this.config.summary.triton.numCTAs || 1;
        this.tritonEnableFpFusion = this.config.summary.triton.enableFpFusion !== false;
        this.tritonCacheDir = this.config.summary.triton.cacheDir || '';
        this.tritonDumpDir = this.config.summary.triton.dumpDir || '';
        this.tritonOverrideArch = this.config.summary.triton.overrideArch || '';
      } else if (this.config.actual) {
        this.tritonBuildThreads = this.config.actual.tritonBuildThreads || 1;
        this.tritonCacheEnabled = this.config.actual.tritonCacheEnabled !== false;
        this.tritonVerbose = this.config.actual.tritonVerbose || false;
        this.tritonAlwaysCompile = this.config.actual.tritonAlwaysCompile || false;
        this.tritonNumWarps = this.config.actual.tritonNumWarps || 0;
        this.tritonNumStages = this.config.actual.tritonNumStages || 0;
        this.tritonNumCTAs = this.config.actual.tritonNumCTAs || 1;
        this.tritonEnableFpFusion = this.config.actual.tritonEnableFpFusion !== false;
        this.tritonCacheDir = this.config.actual.tritonCacheDir || '';
        this.tritonDumpDir = this.config.actual.tritonDumpDir || '';
        this.tritonOverrideArch = this.config.actual.tritonOverrideArch || '';
      }
    }
  }

  // Helper to convert bytes to MB
  bytesToMb(bytes: number): number {
    if (!bytes || bytes <= 0) return 0;
    return Math.round(bytes / (1024 * 1024));
  }

  // Helper to convert MB to bytes
  private mbToBytes(mb: number): number {
    if (!mb || mb <= 0) return 0;
    return mb * 1024 * 1024;
  }

  close(): void {
    if (this.dialogRef) {
      this.dialogRef.close();
    }
  }

  applyPreset(presetName: string): void {
    this.loading = true;
    this.nd4jService.applyPreset(presetName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`Preset '${presetName}' applied successfully`, 'Close', { duration: 3000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to apply preset: ${err.message}`, 'Close', { duration: 5000 });
          this.loading = false;
        }
      });
  }

  updateThreads(): void {
    this.loading = true;
    this.nd4jService.updateThreadSettings(this.maxThreads, this.maxMasterThreads)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Thread settings updated', 'Close', { duration: 3000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to update threads: ${err.message}`, 'Close', { duration: 5000 });
          this.loading = false;
        }
      });
  }

  updateOmpThreads(): void {
    this.loading = true;
    this.nd4jService.updateOmpSettings(this.ompNumThreads)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('OpenMP threads updated', 'Close', { duration: 3000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to update OMP threads: ${err.message}`, 'Close', { duration: 5000 });
          this.loading = false;
        }
      });
  }

  updateOpenBlasThreads(): void {
    this.loading = true;
    this.nd4jService.updateBlasSettings(undefined, this.openBlasThreads)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('OpenBLAS threads updated', 'Close', { duration: 3000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to update OpenBLAS threads: ${err.message}`, 'Close', { duration: 5000 });
          this.loading = false;
        }
      });
  }

  // Change handlers for sliders - trigger debounced updates
  onMaxThreadsChange(): void {
    this.maxThreadsChange$.next(this.maxThreads);
  }

  onMaxMasterThreadsChange(): void {
    this.maxMasterThreadsChange$.next(this.maxMasterThreads);
  }

  onOmpThreadsChange(): void {
    this.ompThreadsChange$.next(this.ompNumThreads);
  }

  onOpenBlasThreadsChange(): void {
    this.openBlasThreadsChange$.next(this.openBlasThreads);
  }

  // Apply methods called by debounced subjects
  private applyMaxThreads(value: number): void {
    this.nd4jService.updateThreadSettings(value, this.maxMasterThreads)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.snackBar.open(`Max threads set to ${value}`, 'Close', { duration: 2000 }),
        error: (err) => this.snackBar.open(`Failed: ${err.message}`, 'Close', { duration: 3000 })
      });
  }

  private applyMaxMasterThreads(value: number): void {
    this.nd4jService.updateThreadSettings(this.maxThreads, value)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.snackBar.open(`Max master threads set to ${value}`, 'Close', { duration: 2000 }),
        error: (err) => this.snackBar.open(`Failed: ${err.message}`, 'Close', { duration: 3000 })
      });
  }

  private applyOmpThreads(value: number): void {
    this.nd4jService.updateOmpSettings(value)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.snackBar.open(`OpenMP threads set to ${value}`, 'Close', { duration: 2000 }),
        error: (err) => this.snackBar.open(`Failed: ${err.message}`, 'Close', { duration: 3000 })
      });
  }

  private applyOpenBlasThreads(value: number): void {
    this.nd4jService.updateBlasSettings(undefined, value)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.snackBar.open(`OpenBLAS threads set to ${value}`, 'Close', { duration: 2000 }),
        error: (err) => this.snackBar.open(`Failed: ${err.message}`, 'Close', { duration: 3000 })
      });
  }

  toggleLifecycleTracking(): void {
    this.loading = true;
    this.nd4jService.setLifecycleTracking(this.lifecycleTracking)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`Lifecycle tracking ${this.lifecycleTracking ? 'enabled' : 'disabled'}`, 'Close', { duration: 3000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to toggle lifecycle tracking: ${err.message}`, 'Close', { duration: 5000 });
          this.loading = false;
        }
      });
  }

  resetToDefaults(): void {
    this.loading = true;
    this.nd4jService.resetConfiguration()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Configuration reset to defaults', 'Close', { duration: 3000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to reset: ${err.message}`, 'Close', { duration: 5000 });
          this.loading = false;
        }
      });
  }

  // Triton settings update
  updateTritonSetting(field: string, value: any): void {
    const settings: any = {};
    settings[field] = value;
    this.nd4jService.updateTritonSettings(settings)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`Triton ${field} updated`, 'Close', { duration: 2000 });
        },
        error: (err: any) => {
          this.snackBar.open(`Failed to update Triton ${field}: ${err.message}`, 'Close', { duration: 3000 });
        }
      });
  }

  saveAllTritonSettings(): void {
    this.loading = true;
    this.nd4jService.updateTritonSettings({
      buildThreads: this.tritonBuildThreads,
      cacheEnabled: this.tritonCacheEnabled,
      verbose: this.tritonVerbose,
      alwaysCompile: this.tritonAlwaysCompile,
      numWarps: this.tritonNumWarps,
      numStages: this.tritonNumStages,
      numCTAs: this.tritonNumCTAs,
      enableFpFusion: this.tritonEnableFpFusion,
      cacheDir: this.tritonCacheDir || undefined,
      dumpDir: this.tritonDumpDir || undefined,
      overrideArch: this.tritonOverrideArch || undefined
    })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Triton settings saved', 'Close', { duration: 3000 });
          this.loadConfiguration();
        },
        error: (err: any) => {
          this.snackBar.open(`Failed to save Triton settings: ${err.message}`, 'Close', { duration: 5000 });
          this.loading = false;
        }
      });
  }

  getPresetKeys(): string[] {
    return this.presets ? Object.keys(this.presets.presets) : [];
  }

  getPresetDescription(preset: string): string {
    return this.presets?.presets?.[preset]?.description || '';
  }

  // Memory change handlers
  onMaxPrimaryMemoryChange(): void {
    this.maxPrimaryMemoryChange$.next(this.maxPrimaryMemoryMb);
  }

  onMaxSpecialMemoryChange(): void {
    this.maxSpecialMemoryChange$.next(this.maxSpecialMemoryMb);
  }

  onMaxDeviceMemoryChange(): void {
    this.maxDeviceMemoryChange$.next(this.maxDeviceMemoryMb);
  }

  // Memory apply methods
  private applyMaxPrimaryMemory(valueMb: number): void {
    const bytes = this.mbToBytes(valueMb);
    this.nd4jService.updateMemorySettings(bytes, undefined, undefined)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.snackBar.open(`Max primary memory set to ${valueMb} MB`, 'Close', { duration: 2000 }),
        error: (err) => this.snackBar.open(`Failed: ${err.message}`, 'Close', { duration: 3000 })
      });
  }

  private applyMaxSpecialMemory(valueMb: number): void {
    const bytes = this.mbToBytes(valueMb);
    this.nd4jService.updateMemorySettings(undefined, bytes, undefined)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.snackBar.open(`Max special memory set to ${valueMb} MB`, 'Close', { duration: 2000 }),
        error: (err) => this.snackBar.open(`Failed: ${err.message}`, 'Close', { duration: 3000 })
      });
  }

  private applyMaxDeviceMemory(valueMb: number): void {
    const bytes = this.mbToBytes(valueMb);
    this.nd4jService.updateMemorySettings(undefined, undefined, bytes)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.snackBar.open(`Max device memory set to ${valueMb} MB`, 'Close', { duration: 2000 }),
        error: (err) => this.snackBar.open(`Failed: ${err.message}`, 'Close', { duration: 3000 })
      });
  }

  // Format memory for display
  formatMemory(mb: number): string {
    if (mb === 0) return 'Unlimited';
    if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
    return `${mb} MB`;
  }

  // ========== Debug Settings Methods ==========

  updateDebugSetting(setting: 'debug' | 'verbose' | 'profiling' | 'leaksDetector'): void {
    const settings: any = {};
    settings[setting] = setting === 'debug' ? this.debugEnabled :
                        setting === 'verbose' ? this.verboseEnabled :
                        setting === 'profiling' ? this.profilingEnabled :
                        this.leaksDetectorEnabled;

    this.nd4jService.updateDebugSettings(settings)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`${setting} ${settings[setting] ? 'enabled' : 'disabled'}`, 'Close', { duration: 2000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to update ${setting}: ${err.message}`, 'Close', { duration: 3000 });
          // Revert the toggle
          if (setting === 'debug') this.debugEnabled = !this.debugEnabled;
          if (setting === 'verbose') this.verboseEnabled = !this.verboseEnabled;
          if (setting === 'profiling') this.profilingEnabled = !this.profilingEnabled;
          if (setting === 'leaksDetector') this.leaksDetectorEnabled = !this.leaksDetectorEnabled;
        }
      });
  }

  // ========== Advanced Debug Settings Methods ==========

  updateAdvancedDebugSetting(setting: string): void {
    const settings: any = {};
    settings[setting] = (this as any)[setting];

    this.nd4jService.updateAdvancedDebugSettings(settings)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`${setting} ${settings[setting] ? 'enabled' : 'disabled'}`, 'Close', { duration: 2000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to update ${setting}: ${err.message}`, 'Close', { duration: 3000 });
          (this as any)[setting] = !(this as any)[setting]; // Revert
        }
      });
  }

  // ========== Function Trace Settings Methods ==========

  updateFunctionTraceSetting(setting: string): void {
    const settings: any = {};
    settings[setting] = (this as any)[setting];

    this.nd4jService.updateFunctionTraceSettings(settings)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`${setting} ${settings[setting] ? 'enabled' : 'disabled'}`, 'Close', { duration: 2000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to update ${setting}: ${err.message}`, 'Close', { duration: 3000 });
          (this as any)[setting] = !(this as any)[setting]; // Revert
        }
      });
  }

  // ========== Deletion Settings Methods ==========

  updateDeletionSetting(setting: string): void {
    const settings: any = {};
    settings[setting] = (this as any)[setting];

    this.nd4jService.updateDeletionSettings(settings)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`${setting} ${settings[setting] ? 'enabled' : 'disabled'}`, 'Close', { duration: 2000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to update ${setting}: ${err.message}`, 'Close', { duration: 3000 });
          (this as any)[setting] = !(this as any)[setting]; // Revert
        }
      });
  }

  // ========== Core Settings Methods ==========

  updateCoreSetting(setting: 'enableBlas' | 'helpersAllowed'): void {
    const settings: any = {};
    settings[setting] = (this as any)[setting];

    this.nd4jService.updateCoreSettings(settings)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`${setting} ${settings[setting] ? 'enabled' : 'disabled'}`, 'Close', { duration: 2000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to update ${setting}: ${err.message}`, 'Close', { duration: 3000 });
          (this as any)[setting] = !(this as any)[setting]; // Revert
        }
      });
  }

  // ========== JavaCPP Settings Methods ==========

  updateJavacppSetting(setting: 'loggerDebug' | 'pathsFirst'): void {
    const value = setting === 'loggerDebug' ? this.javacppLoggerDebug : this.javacppPathsFirst;
    const settings: any = {};
    settings[setting] = value;

    this.nd4jService.updateJavacppSettings(settings)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`JavaCPP ${setting} ${value ? 'enabled' : 'disabled'}`, 'Close', { duration: 2000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to update ${setting}: ${err.message}`, 'Close', { duration: 3000 });
          if (setting === 'loggerDebug') this.javacppLoggerDebug = !this.javacppLoggerDebug;
          if (setting === 'pathsFirst') this.javacppPathsFirst = !this.javacppPathsFirst;
        }
      });
  }

  // ========== BLAS Settings Methods ==========

  updateBlasSerializationSetting(): void {
    this.nd4jService.updateBlasSettings(this.blasSerializationEnabled, undefined)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`BLAS serialization ${this.blasSerializationEnabled ? 'enabled' : 'disabled'}`, 'Close', { duration: 2000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to update BLAS serialization: ${err.message}`, 'Close', { duration: 3000 });
          this.blasSerializationEnabled = !this.blasSerializationEnabled; // Revert
        }
      });
  }

  // ========== Performance Threshold Methods ==========

  updateThresholds(): void {
    this.nd4jService.updatePerformanceThresholds(this.tadThreshold, this.elementwiseThreshold)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Performance thresholds updated', 'Close', { duration: 2000 });
          this.loadConfiguration();
        },
        error: (err) => {
          this.snackBar.open(`Failed to update thresholds: ${err.message}`, 'Close', { duration: 3000 });
        }
      });
  }
}
