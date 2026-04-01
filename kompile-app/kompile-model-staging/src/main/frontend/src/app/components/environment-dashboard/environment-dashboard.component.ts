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
import {
  EnvironmentService,
  EnvironmentSettings,
  MemorySettings,
  CudaSettings,
  TritonSettings,
  DspSettings,
  DebugSettings,
  LifecycleSettings,
  DeviceCacheStatusResponse,
  LlmConfigPreset
} from '../../services/environment.service';

@Component({
  selector: 'app-environment-dashboard',
  standalone: false,
  templateUrl: './environment-dashboard.component.html',
  styleUrls: ['./environment-dashboard.component.css']
})
export class EnvironmentDashboardComponent implements OnInit {

  environment: EnvironmentSettings | null = null;
  memorySettings: MemorySettings | null = null;
  isLoading = true;
  error: string | null = null;
  selectedTabIndex = 0;

  // Editable copies for each section
  editMemory = { maxPrimaryMemory: 0, maxSpecialMemory: 0, maxDeviceMemory: 0, enableBlas: true };
  editPerformance = { tadThreshold: 0, elementwiseThreshold: 0, maxThreads: 0, maxMasterThreads: 0 };
  editDebug: DebugSettings = {
    verbose: false, debug: false, profiling: false, detectingLeaks: false,
    helpersAllowed: true, logNativeNDArrayCreation: false, checkOutputChange: false, checkInputChange: false
  };
  editLifecycle: LifecycleSettings = {
    lifecycleTracking: false, trackViews: false, trackDeletions: false,
    ndArrayTracking: false, dataBufferTracking: false,
    stackDepth: 0, reportInterval: 0, maxDeletionHistory: 0
  };
  editCuda: Partial<CudaSettings> = {};
  editTriton: Partial<TritonSettings> = {};
  editDsp: Partial<DspSettings> = {};

  // Device & Native Cache
  deviceCacheStatus: DeviceCacheStatusResponse | null = null;
  loadingDeviceCacheStatus = false;

  // LLM Config Presets
  llmPresets: LlmConfigPreset[] = [];
  applyingLlmPreset: string | null = null;

  saving: { [key: string]: boolean } = {};

  constructor(
    private environmentService: EnvironmentService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadEnvironment();
    this.loadDeviceCacheStatus();
    this.loadLlmPresets();
  }

  loadEnvironment(): void {
    this.isLoading = true;
    this.error = null;

    this.environmentService.getEnvironment().subscribe({
      next: (env) => {
        this.environment = env;
        this.populateEditForms(env);
        this.isLoading = false;
      },
      error: (err) => {
        this.error = 'Failed to load environment settings: ' + err.message;
        this.isLoading = false;
      }
    });

    this.environmentService.getMemorySettings().subscribe({
      next: (mem) => {
        this.memorySettings = mem;
      },
      error: () => { /* Memory info is supplementary */ }
    });
  }

  private populateEditForms(env: EnvironmentSettings): void {
    this.editMemory = {
      maxPrimaryMemory: env.maxPrimaryMemory,
      maxSpecialMemory: env.maxSpecialMemory,
      maxDeviceMemory: env.maxDeviceMemory,
      enableBlas: env.enableBlas
    };
    this.editPerformance = {
      tadThreshold: env.tadThreshold,
      elementwiseThreshold: env.elementwiseThreshold,
      maxThreads: env.maxThreads,
      maxMasterThreads: env.maxMasterThreads
    };
    this.editDebug = {
      verbose: env.verbose,
      debug: env.debug,
      profiling: env.profiling,
      detectingLeaks: env.detectingLeaks,
      helpersAllowed: env.helpersAllowed,
      logNativeNDArrayCreation: env.logNativeNDArrayCreation,
      checkOutputChange: env.checkOutputChange,
      checkInputChange: env.checkInputChange
    };
    this.editLifecycle = {
      lifecycleTracking: env.lifecycleTracking,
      trackViews: env.trackViews,
      trackDeletions: env.trackDeletions,
      ndArrayTracking: env.ndArrayTracking,
      dataBufferTracking: env.dataBufferTracking,
      stackDepth: env.stackDepth,
      reportInterval: env.reportInterval,
      maxDeletionHistory: env.maxDeletionHistory
    };
    if (env.cuda) {
      this.editCuda = { ...env.cuda };
    }
    if (env.triton) {
      this.editTriton = { ...env.triton };
    }
    if (env.dsp) {
      this.editDsp = { ...env.dsp };
    }
  }

  // ==================== Save Handlers ====================

  saveMemorySettings(): void {
    this.saving['memory'] = true;
    this.environmentService.updateEnvironment({
      maxPrimaryMemory: this.editMemory.maxPrimaryMemory,
      maxSpecialMemory: this.editMemory.maxSpecialMemory,
      maxDeviceMemory: this.editMemory.maxDeviceMemory,
      enableBlas: this.editMemory.enableBlas
    } as any).subscribe({
      next: (res) => {
        this.saving['memory'] = false;
        this.snackBar.open(res.message || 'Memory settings saved', 'OK', { duration: 3000 });
        this.loadEnvironment();
      },
      error: (err) => {
        this.saving['memory'] = false;
        this.snackBar.open('Failed to save: ' + err.message, 'OK', { duration: 5000 });
      }
    });
  }

  savePerformanceSettings(): void {
    this.saving['performance'] = true;
    this.environmentService.updateEnvironment({
      tadThreshold: this.editPerformance.tadThreshold,
      elementwiseThreshold: this.editPerformance.elementwiseThreshold,
      maxThreads: this.editPerformance.maxThreads,
      maxMasterThreads: this.editPerformance.maxMasterThreads
    } as any).subscribe({
      next: (res) => {
        this.saving['performance'] = false;
        this.snackBar.open(res.message || 'Performance settings saved', 'OK', { duration: 3000 });
        this.loadEnvironment();
      },
      error: (err) => {
        this.saving['performance'] = false;
        this.snackBar.open('Failed to save: ' + err.message, 'OK', { duration: 5000 });
      }
    });
  }

  saveDebugSettings(): void {
    this.saving['debug'] = true;
    this.environmentService.updateDebugSettings(this.editDebug).subscribe({
      next: (res) => {
        this.saving['debug'] = false;
        this.snackBar.open(res.message || 'Debug settings saved', 'OK', { duration: 3000 });
        this.loadEnvironment();
      },
      error: (err) => {
        this.saving['debug'] = false;
        this.snackBar.open('Failed to save: ' + err.message, 'OK', { duration: 5000 });
      }
    });
  }

  saveLifecycleSettings(): void {
    this.saving['lifecycle'] = true;
    this.environmentService.updateLifecycleSettings(this.editLifecycle).subscribe({
      next: (res) => {
        this.saving['lifecycle'] = false;
        this.snackBar.open(res.message || 'Lifecycle settings saved', 'OK', { duration: 3000 });
        this.loadEnvironment();
      },
      error: (err) => {
        this.saving['lifecycle'] = false;
        this.snackBar.open('Failed to save: ' + err.message, 'OK', { duration: 5000 });
      }
    });
  }

  saveCudaSettings(): void {
    this.saving['cuda'] = true;
    this.environmentService.updateCudaSettings(this.editCuda).subscribe({
      next: (res) => {
        this.saving['cuda'] = false;
        this.snackBar.open(res.message || 'CUDA settings saved', 'OK', { duration: 3000 });
        this.loadEnvironment();
      },
      error: (err) => {
        this.saving['cuda'] = false;
        this.snackBar.open('Failed to save: ' + err.message, 'OK', { duration: 5000 });
      }
    });
  }

  saveTritonSettings(): void {
    this.saving['triton'] = true;
    this.environmentService.updateTritonSettings(this.editTriton).subscribe({
      next: (res) => {
        this.saving['triton'] = false;
        this.snackBar.open(res.message || 'Triton settings saved', 'OK', { duration: 3000 });
        this.loadEnvironment();
      },
      error: (err) => {
        this.saving['triton'] = false;
        this.snackBar.open('Failed to save: ' + err.message, 'OK', { duration: 5000 });
      }
    });
  }

  saveDspSettings(): void {
    this.saving['dsp'] = true;
    this.environmentService.updateDspSettings(this.editDsp).subscribe({
      next: (res) => {
        this.saving['dsp'] = false;
        this.snackBar.open(res.message || 'DSP settings saved', 'OK', { duration: 3000 });
        this.loadEnvironment();
      },
      error: (err) => {
        this.saving['dsp'] = false;
        this.snackBar.open('Failed to save: ' + err.message, 'OK', { duration: 5000 });
      }
    });
  }

  // ==================== LLM Config Presets ====================

  loadLlmPresets(): void {
    this.environmentService.getLlmConfigPresets().subscribe({
      next: (presets) => { this.llmPresets = presets; },
      error: () => { /* LLM presets are supplementary */ }
    });
  }

  applyLlmConfig(presetId: string): void {
    this.applyingLlmPreset = presetId;
    this.environmentService.applyLlmConfig(presetId).subscribe({
      next: (res) => {
        this.applyingLlmPreset = null;
        this.snackBar.open(res.message || 'LLM config applied', 'OK', { duration: 3000 });
        this.loadEnvironment();
      },
      error: (err) => {
        this.applyingLlmPreset = null;
        this.snackBar.open('Failed to apply LLM config: ' + err.message, 'OK', { duration: 5000 });
      }
    });
  }

  // ==================== Device & Native Cache ====================

  loadDeviceCacheStatus(): void {
    this.loadingDeviceCacheStatus = true;
    this.environmentService.getDeviceCacheStatus().subscribe({
      next: (status) => {
        this.deviceCacheStatus = status;
        this.loadingDeviceCacheStatus = false;
      },
      error: () => {
        this.loadingDeviceCacheStatus = false;
      }
    });
  }

  clearNativeCache(type: string): void {
    this.saving['nativeCache'] = true;
    this.environmentService.clearNativeCache(type).subscribe({
      next: (res) => {
        this.saving['nativeCache'] = false;
        this.snackBar.open(res.message || 'Native cache cleared', 'OK', { duration: 3000 });
        this.loadDeviceCacheStatus();
      },
      error: (err) => {
        this.saving['nativeCache'] = false;
        this.snackBar.open('Failed to clear cache: ' + err.message, 'OK', { duration: 5000 });
      }
    });
  }

  getMemoryUsagePercent(device: any): number {
    if (!device || device.totalMemoryBytes === 0) return 0;
    return Math.round((device.usedMemoryBytes / device.totalMemoryBytes) * 100);
  }

  getMemoryBarColor(percent: number): string {
    if (percent >= 90) return '#ef5350';
    if (percent >= 70) return '#ff9800';
    if (percent >= 50) return '#ffb74d';
    return '#81c784';
  }

  // ==================== Utilities ====================

  formatBytes(bytes: number): string {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }

  getBackendLabel(): string {
    if (!this.environment) return 'Unknown';
    return this.environment.cpu ? 'CPU' : 'GPU (CUDA)';
  }

  getBlasVersion(): string {
    if (!this.environment) return 'N/A';
    return `${this.environment.blasMajorVersion}.${this.environment.blasMinorVersion}.${this.environment.blasPatchVersion}`;
  }
}
