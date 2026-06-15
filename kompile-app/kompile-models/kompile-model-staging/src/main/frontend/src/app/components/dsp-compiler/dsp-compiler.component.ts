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

import { Component, OnInit, OnDestroy, NgZone, ViewChild, ElementRef } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subscription } from 'rxjs';
import {
  CompilerService,
  OptimizationPassInfo,
  OptimizationProfileInfo,
  CompilerOptimizeRequest,
  CompilerOptimizeResponse,
  GraphInfoResponse,
  CompilerCompareResponse,
  TritonConfigResponse,
  TritonCompileRequest,
  CacheStatusResponse,
  DeviceCacheStatusResponse,
  CompilationJobStatus,
  CompilationLogEntry,
  CompilationRequest,
  SaveCompiledGraphRequest,
  SaveCompiledGraphResponse,
  CompiledModelInfo,
  DSP_COMPILATION_MODES,
  GRAPH_EXECUTION_MODES
} from '../../services/compiler.service';
import { StagingService } from '../../services/staging.service';
import { ModelEntry } from '../../models/api-models';

@Component({
  selector: 'app-dsp-compiler',
  standalone: false,
  templateUrl: './dsp-compiler.component.html',
  styleUrls: ['./dsp-compiler.component.css']
})
export class DspCompilerComponent implements OnInit, OnDestroy {

  // Tab state
  activeTab = 0;

  // Data
  models: ModelEntry[] = [];
  passes: OptimizationPassInfo[] = [];
  profiles: OptimizationProfileInfo[] = [];

  // Loading states
  loadingModels = false;
  loadingPasses = false;
  loadingProfiles = false;

  // ==================== Tab 1: Graph Optimizer ====================
  optimizerModelId = '';
  selectedProfile = '';
  selectedPasses: Set<string> = new Set();
  maxIterations = 3;
  dryRun = false;
  createBackup = true;
  isOptimizing = false;
  optimizeResult: CompilerOptimizeResponse | null = null;

  // ==================== Tab 2: Graph Inspector ====================
  inspectorModelId = '';
  loadingGraphInfo = false;
  graphInfo: GraphInfoResponse | null = null;
  opsDisplayedColumns: string[] = ['name', 'opType', 'inputs', 'outputs'];

  // ==================== Tab 3: Model Comparison ====================
  compareModel1Id = '';
  compareModel2Id = '';
  isComparing = false;
  compareResult: CompilerCompareResponse | null = null;

  // ==================== Tab 4: Triton Compiler ====================
  tritonModelId = '';
  tritonConfig: TritonConfigResponse | null = null;
  loadingTritonConfig = false;
  tritonNumWarps = 0;
  tritonNumStages = 0;
  tritonNumCTAs = 1;
  tritonFpFusion = true;
  tritonArch = '';
  isTritonCompiling = false;
  tritonResult: CompilerOptimizeResponse | null = null;

  // ==================== Tab 5: Cache Management ====================
  cacheStatus: CacheStatusResponse | null = null;
  loadingCacheStatus = false;
  deviceCacheStatus: DeviceCacheStatusResponse | null = null;
  loadingDeviceCacheStatus = false;

  // ==================== Tab 6: Compile Jobs ====================
  compilationModes = DSP_COMPILATION_MODES;
  executionModes = GRAPH_EXECUTION_MODES;
  compileModelId = '';
  compileMode = 'REDUCE_OVERHEAD';
  compileExecMode = 'AUTO';
  compileProfile = '';
  compileEnableCache = true;
  jobs: CompilationJobStatus[] = [];
  loadingJobs = false;
  activeJobId: string | null = null;
  jobLogs: CompilationLogEntry[] = [];
  logAutoScroll = true;
  logLevelFilter = 'ALL';
  logSearchTerm = '';
  private jobEventSource: EventSource | null = null;
  private jobPollInterval: any = null;
  @ViewChild('logContainer') logContainer!: ElementRef;

  // ==================== Tab 7: Compiled Graphs ====================
  compiledModels: CompiledModelInfo[] = [];
  loadingCompiledModels = false;
  savingCompiledGraph = false;
  saveSourceModelId = '';
  saveOutputModelId = '';
  saveTargetOutputs = '';
  saveProfile = '';
  saveSelectedPasses: Set<string> = new Set();
  saveDescription = '';
  saveResult: SaveCompiledGraphResponse | null = null;

  // Save-as toggle for Tab 1 (Graph Optimizer)
  optimizerSaveAsEnabled = false;
  optimizerOutputModelId = '';

  // Output model ID for Tab 6 (Compile Jobs)
  compileOutputModelId = '';

  private subscriptions: Subscription[] = [];

  constructor(
    private compilerService: CompilerService,
    private stagingService: StagingService,
    private snackBar: MatSnackBar,
    private ngZone: NgZone
  ) {}

  ngOnInit(): void {
    this.loadModels();
    this.loadPasses();
    this.loadProfiles();
    this.loadTritonConfig();
    this.loadCacheStatus();
    this.loadDeviceCacheStatus();
    this.loadJobs();
    this.loadCompiledModels();
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach(s => s.unsubscribe());
    this.disconnectJobStream();
    if (this.jobPollInterval) {
      clearInterval(this.jobPollInterval);
    }
  }

  // ==================== Data Loading ====================

  loadModels(): void {
    this.loadingModels = true;
    const sub = this.stagingService.getRegistry().subscribe({
      next: (registry) => {
        this.models = Object.values(registry.models || {});
        this.loadingModels = false;
      },
      error: (err) => {
        console.error('Failed to load models:', err);
        this.loadingModels = false;
        this.snackBar.open('Failed to load models', 'Close', { duration: 3000 });
      }
    });
    this.subscriptions.push(sub);
  }

  loadPasses(): void {
    this.loadingPasses = true;
    const sub = this.compilerService.getAvailablePasses().subscribe({
      next: (passes) => {
        this.passes = passes;
        this.loadingPasses = false;
        // Select default passes
        this.passes.filter(p => p.isDefault).forEach(p => this.selectedPasses.add(p.id));
      },
      error: (err) => {
        console.error('Failed to load passes:', err);
        this.loadingPasses = false;
        this.snackBar.open('Failed to load optimization passes', 'Close', { duration: 3000 });
      }
    });
    this.subscriptions.push(sub);
  }

  loadProfiles(): void {
    this.loadingProfiles = true;
    const sub = this.compilerService.getProfiles().subscribe({
      next: (profiles) => {
        this.profiles = profiles;
        this.loadingProfiles = false;
      },
      error: (err) => {
        console.error('Failed to load profiles:', err);
        this.loadingProfiles = false;
      }
    });
    this.subscriptions.push(sub);
  }

  loadTritonConfig(): void {
    this.loadingTritonConfig = true;
    const sub = this.compilerService.getTritonConfig().subscribe({
      next: (config) => {
        this.tritonConfig = config;
        this.tritonNumWarps = config.tritonNumWarps;
        this.tritonNumStages = config.tritonNumStages;
        this.tritonNumCTAs = config.tritonNumCTAs;
        this.tritonFpFusion = config.tritonEnableFpFusion;
        this.tritonArch = config.tritonOverrideArch || '';
        this.loadingTritonConfig = false;
      },
      error: (err) => {
        console.error('Failed to load Triton config:', err);
        this.loadingTritonConfig = false;
      }
    });
    this.subscriptions.push(sub);
  }

  // ==================== Pass/Profile Helpers ====================

  getPassesByCategory(category: string): OptimizationPassInfo[] {
    return this.passes.filter(p => p.category === category);
  }

  getCategories(): string[] {
    const categories = new Set(this.passes.map(p => p.category));
    return Array.from(categories);
  }

  getCategoryLabel(category: string): string {
    switch (category) {
      case 'CLEANUP': return 'Cleanup';
      case 'FUSION': return 'Fusion';
      case 'GPU': return 'GPU';
      case 'QUANTIZATION': return 'Quantization';
      default: return category;
    }
  }

  getCategoryIcon(category: string): string {
    switch (category) {
      case 'CLEANUP': return 'cleaning_services';
      case 'FUSION': return 'merge_type';
      case 'GPU': return 'memory';
      case 'QUANTIZATION': return 'compress';
      default: return 'settings';
    }
  }

  isPassSelected(passId: string): boolean {
    return this.selectedPasses.has(passId);
  }

  togglePass(passId: string): void {
    if (this.selectedPasses.has(passId)) {
      this.selectedPasses.delete(passId);
    } else {
      this.selectedPasses.add(passId);
    }
    // Clear profile selection when manually toggling
    this.selectedProfile = '';
  }

  selectProfile(profileName: string): void {
    this.selectedProfile = profileName;
    this.selectedPasses.clear();
    const profile = this.profiles.find(p => p.profileName === profileName);
    if (profile) {
      profile.includedPasses.forEach(p => this.selectedPasses.add(p));
    }
  }

  selectAllPasses(): void {
    this.passes.forEach(p => this.selectedPasses.add(p.id));
    this.selectedProfile = '';
  }

  clearAllPasses(): void {
    this.selectedPasses.clear();
    this.selectedProfile = '';
  }

  // ==================== Tab 1: Graph Optimizer ====================

  runOptimization(): void {
    if (!this.optimizerModelId || this.selectedPasses.size === 0) {
      this.snackBar.open('Select a model and at least one pass', 'Close', { duration: 3000 });
      return;
    }

    this.isOptimizing = true;
    this.optimizeResult = null;

    const request: CompilerOptimizeRequest = {
      modelId: this.optimizerModelId,
      selectedPasses: Array.from(this.selectedPasses),
      profile: this.selectedProfile || undefined,
      maxIterations: this.maxIterations,
      dryRun: this.dryRun,
      createBackup: this.createBackup,
      force: false,
      outputModelId: this.optimizerSaveAsEnabled && this.optimizerOutputModelId ? this.optimizerOutputModelId : undefined
    };

    const sub = this.compilerService.optimize(request).subscribe({
      next: (result) => {
        this.optimizeResult = result;
        this.isOptimizing = false;
        if (result.success) {
          this.snackBar.open('Optimization completed successfully', 'Close', { duration: 3000 });
        } else {
          this.snackBar.open(`Optimization failed: ${result.error}`, 'Close', { duration: 5000 });
        }
      },
      error: (err) => {
        this.isOptimizing = false;
        this.optimizeResult = {
          jobId: '',
          status: 'FAILED',
          success: false,
          modelId: this.optimizerModelId,
          error: err.message,
          opsRemoved: 0,
          opsFused: 0,
          passesApplied: [],
          beforeOpsCount: 0,
          afterOpsCount: 0,
          beforeVarsCount: 0,
          afterVarsCount: 0,
          sizeBeforeBytes: 0,
          sizeAfterBytes: 0,
          reductionPercent: 0,
          optimizationTimeMs: 0,
          dryRun: false
        };
        this.snackBar.open(`Optimization failed: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
  }

  // ==================== Tab 2: Graph Inspector ====================

  inspectGraph(): void {
    if (!this.inspectorModelId) {
      this.snackBar.open('Select a model to inspect', 'Close', { duration: 3000 });
      return;
    }

    this.loadingGraphInfo = true;
    this.graphInfo = null;

    const sub = this.compilerService.getGraphInfo(this.inspectorModelId).subscribe({
      next: (info) => {
        this.graphInfo = info;
        this.loadingGraphInfo = false;
      },
      error: (err) => {
        console.error('Failed to get graph info:', err);
        this.loadingGraphInfo = false;
        this.snackBar.open(`Failed to inspect graph: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
  }

  getOpTypeEntries(): { key: string; value: number }[] {
    if (!this.graphInfo?.opTypes) return [];
    return Object.entries(this.graphInfo.opTypes).map(([key, value]) => ({ key, value }));
  }

  getDataTypeEntries(): { key: string; value: number }[] {
    if (!this.graphInfo?.analysis?.parametersByDataType) return [];
    return Object.entries(this.graphInfo.analysis.parametersByDataType).map(([key, value]) => ({ key, value }));
  }

  // ==================== Tab 3: Model Comparison ====================

  runComparison(): void {
    if (!this.compareModel1Id || !this.compareModel2Id) {
      this.snackBar.open('Select two models to compare', 'Close', { duration: 3000 });
      return;
    }

    this.isComparing = true;
    this.compareResult = null;

    const sub = this.compilerService.compareGraphs(this.compareModel1Id, this.compareModel2Id).subscribe({
      next: (result) => {
        this.compareResult = result;
        this.isComparing = false;
        if (result.success) {
          this.snackBar.open('Comparison completed', 'Close', { duration: 3000 });
        } else {
          this.snackBar.open(`Comparison failed: ${result.error}`, 'Close', { duration: 5000 });
        }
      },
      error: (err) => {
        this.isComparing = false;
        this.compareResult = {
          success: false,
          error: err.message,
          opsAdded: 0,
          opsRemoved: 0,
          opsChanged: 0,
          sizeChange: 0,
          maxAbsoluteDifference: 0,
          meanAbsoluteDifference: 0,
          outputsMatch: false,
          speedupFactor: 0
        };
        this.snackBar.open(`Comparison failed: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
  }

  // ==================== Tab 4: Triton Compiler ====================

  runTritonCompile(): void {
    if (!this.tritonModelId) {
      this.snackBar.open('Select a model for Triton compilation', 'Close', { duration: 3000 });
      return;
    }

    this.isTritonCompiling = true;
    this.tritonResult = null;

    const request: TritonCompileRequest = {
      modelId: this.tritonModelId,
      numWarps: this.tritonNumWarps,
      numStages: this.tritonNumStages,
      numCTAs: this.tritonNumCTAs,
      fpFusion: this.tritonFpFusion,
      arch: this.tritonArch || undefined
    };

    const sub = this.compilerService.compileWithTriton(request).subscribe({
      next: (result) => {
        this.tritonResult = result;
        this.isTritonCompiling = false;
        if (result.success) {
          this.snackBar.open('Triton compilation completed', 'Close', { duration: 3000 });
        } else {
          this.snackBar.open(`Triton compilation failed: ${result.error}`, 'Close', { duration: 5000 });
        }
      },
      error: (err) => {
        this.isTritonCompiling = false;
        this.tritonResult = {
          jobId: '',
          status: 'FAILED',
          success: false,
          modelId: this.tritonModelId,
          error: err.message,
          opsRemoved: 0,
          opsFused: 0,
          passesApplied: [],
          beforeOpsCount: 0,
          afterOpsCount: 0,
          beforeVarsCount: 0,
          afterVarsCount: 0,
          sizeBeforeBytes: 0,
          sizeAfterBytes: 0,
          reductionPercent: 0,
          optimizationTimeMs: 0,
          dryRun: false
        };
        this.snackBar.open(`Triton compilation failed: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
  }

  // ==================== Tab 5: Cache Management ====================

  loadCacheStatus(): void {
    this.loadingCacheStatus = true;
    const sub = this.compilerService.getCacheStatus().subscribe({
      next: (status) => {
        this.cacheStatus = status;
        this.loadingCacheStatus = false;
      },
      error: (err) => {
        console.error('Failed to load cache status:', err);
        this.loadingCacheStatus = false;
      }
    });
    this.subscriptions.push(sub);
  }

  clearCache(type: string): void {
    const sub = this.compilerService.clearCache(type).subscribe({
      next: () => {
        this.snackBar.open(`Cache cleared: ${type}`, 'Close', { duration: 3000 });
        this.loadCacheStatus();
      },
      error: (err) => {
        this.snackBar.open(`Failed to clear cache: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
  }

  toggleCacheEnabled(type: string, enabled: boolean): void {
    const sub = this.compilerService.setCacheEnabled(type, enabled).subscribe({
      next: () => {
        this.snackBar.open(`Cache ${enabled ? 'enabled' : 'disabled'}: ${type}`, 'Close', { duration: 3000 });
        this.loadCacheStatus();
      },
      error: (err) => {
        this.snackBar.open(`Failed to update cache: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
  }

  // ==================== Device & Native Cache ====================

  loadDeviceCacheStatus(): void {
    this.loadingDeviceCacheStatus = true;
    const sub = this.compilerService.getDeviceCacheStatus().subscribe({
      next: (status) => {
        this.deviceCacheStatus = status;
        this.loadingDeviceCacheStatus = false;
      },
      error: (err) => {
        console.error('Failed to load device cache status:', err);
        this.loadingDeviceCacheStatus = false;
      }
    });
    this.subscriptions.push(sub);
  }

  clearNativeCache(type: string): void {
    const sub = this.compilerService.clearNativeCache(type).subscribe({
      next: () => {
        this.snackBar.open(`Native cache cleared: ${type}`, 'Close', { duration: 3000 });
        this.loadDeviceCacheStatus();
      },
      error: (err) => {
        this.snackBar.open(`Failed to clear native cache: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
  }

  getMemoryUsagePercent(device: any): number {
    if (!device.totalMemoryBytes || device.totalMemoryBytes === 0) return 0;
    return (device.usedMemoryBytes / device.totalMemoryBytes) * 100;
  }

  getMemoryBarColor(percent: number): string {
    if (percent > 90) return '#f44336';
    if (percent > 70) return '#ff9800';
    return '#4caf50';
  }

  // ==================== Tab 6: Compile Jobs ====================

  loadJobs(): void {
    this.loadingJobs = true;
    const sub = this.compilerService.getJobs().subscribe({
      next: (jobs) => {
        this.jobs = jobs;
        this.loadingJobs = false;
      },
      error: (err) => {
        console.error('Failed to load jobs:', err);
        this.loadingJobs = false;
      }
    });
    this.subscriptions.push(sub);
  }

  startCompilation(): void {
    if (!this.compileModelId) {
      this.snackBar.open('Select a model for compilation', 'Close', { duration: 3000 });
      return;
    }

    const request: CompilationRequest = {
      modelId: this.compileModelId,
      compilationMode: this.compileMode,
      executionMode: this.compileExecMode,
      selectedPasses: Array.from(this.selectedPasses),
      profile: this.compileProfile || undefined,
      maxIterations: this.maxIterations,
      createBackup: this.createBackup,
      enableCache: this.compileEnableCache,
      outputModelId: this.compileOutputModelId || undefined
    };

    const sub = this.compilerService.startCompilationJob(request).subscribe({
      next: (status) => {
        this.snackBar.open(`Compilation job started: ${status.jobId}`, 'Close', { duration: 3000 });
        this.jobs.unshift(status);
        this.connectToJobStream(status.jobId);
      },
      error: (err) => {
        this.snackBar.open(`Failed to start compilation: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
  }

  connectToJobStream(jobId: string): void {
    this.disconnectJobStream();
    this.activeJobId = jobId;
    this.jobLogs = [];

    this.ngZone.runOutsideAngular(() => {
      this.jobEventSource = this.compilerService.connectToJobStream(jobId);

      this.jobEventSource.addEventListener('log', (event: any) => {
        const logEntry: CompilationLogEntry = JSON.parse(event.data);
        this.ngZone.run(() => {
          this.jobLogs.push(logEntry);
          if (this.jobLogs.length > 500) {
            this.jobLogs = this.jobLogs.slice(-500);
          }
          this.scrollToBottom();
        });
      });

      this.jobEventSource.addEventListener('status', (event: any) => {
        const status: CompilationJobStatus = JSON.parse(event.data);
        this.ngZone.run(() => {
          const idx = this.jobs.findIndex(j => j.jobId === status.jobId);
          if (idx >= 0) {
            this.jobs[idx] = status;
          }
          if (status.status === 'COMPLETED' || status.status === 'FAILED' || status.status === 'CANCELLED') {
            this.disconnectJobStream();
            this.loadCacheStatus();
            this.loadDeviceCacheStatus();
          }
        });
      });

      this.jobEventSource.onerror = () => {
        this.ngZone.run(() => {
          this.disconnectJobStream();
        });
      };
    });

    // Also poll for job status updates
    this.jobPollInterval = setInterval(() => {
      this.loadJobs();
    }, 3000);
  }

  disconnectJobStream(): void {
    if (this.jobEventSource) {
      this.jobEventSource.close();
      this.jobEventSource = null;
    }
    if (this.jobPollInterval) {
      clearInterval(this.jobPollInterval);
      this.jobPollInterval = null;
    }
  }

  cancelJob(jobId: string): void {
    const sub = this.compilerService.cancelJob(jobId).subscribe({
      next: () => {
        this.snackBar.open('Job cancelled', 'Close', { duration: 3000 });
        this.loadJobs();
      },
      error: (err) => {
        this.snackBar.open(`Failed to cancel job: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
  }

  viewJobLogs(jobId: string): void {
    if (this.activeJobId === jobId) return;

    const job = this.jobs.find(j => j.jobId === jobId);
    if (job && (job.status === 'QUEUED' || job.status === 'COMPILING')) {
      this.connectToJobStream(jobId);
    } else {
      this.activeJobId = jobId;
      this.jobLogs = [];
      const sub = this.compilerService.getJobLogs(jobId).subscribe({
        next: (logs) => {
          this.jobLogs = logs;
          this.scrollToBottom();
        },
        error: (err) => {
          console.error('Failed to load job logs:', err);
        }
      });
      this.subscriptions.push(sub);
    }
  }

  getFilteredLogs(): CompilationLogEntry[] {
    let logs = this.jobLogs;
    if (this.logLevelFilter !== 'ALL') {
      logs = logs.filter(l => l.level === this.logLevelFilter);
    }
    if (this.logSearchTerm) {
      const term = this.logSearchTerm.toLowerCase();
      logs = logs.filter(l => l.message.toLowerCase().includes(term) || l.phase.toLowerCase().includes(term));
    }
    return logs;
  }

  scrollToBottom(): void {
    if (this.logAutoScroll && this.logContainer) {
      setTimeout(() => {
        const el = this.logContainer.nativeElement;
        el.scrollTop = el.scrollHeight;
      }, 50);
    }
  }

  getJobStatusIcon(status: string): string {
    switch (status) {
      case 'QUEUED': return 'schedule';
      case 'COMPILING': return 'build';
      case 'COMPLETED': return 'check_circle';
      case 'FAILED': return 'error';
      case 'CANCELLED': return 'cancel';
      default: return 'help';
    }
  }

  getJobStatusColor(status: string): string {
    switch (status) {
      case 'QUEUED': return '#ff9800';
      case 'COMPILING': return '#2196f3';
      case 'COMPLETED': return '#4caf50';
      case 'FAILED': return '#f44336';
      case 'CANCELLED': return '#9e9e9e';
      default: return '#e0e0e0';
    }
  }

  getLogLevelColor(level: string): string {
    switch (level) {
      case 'ERROR': return '#f44336';
      case 'WARN': return '#ff9800';
      case 'INFO': return '#4caf50';
      case 'DEBUG': return '#9e9e9e';
      default: return '#e0e0e0';
    }
  }

  getActiveJob(): CompilationJobStatus | null {
    if (!this.activeJobId) return null;
    return this.jobs.find(j => j.jobId === this.activeJobId) || null;
  }

  // ==================== Tab 7: Compiled Graphs ====================

  loadCompiledModels(): void {
    this.loadingCompiledModels = true;
    const sub = this.compilerService.listCompiledModels().subscribe({
      next: (models) => {
        this.compiledModels = models;
        this.loadingCompiledModels = false;
      },
      error: (err) => {
        console.error('Failed to load compiled models:', err);
        this.loadingCompiledModels = false;
      }
    });
    this.subscriptions.push(sub);
  }

  saveCompiledGraph(): void {
    if (!this.saveSourceModelId || !this.saveOutputModelId) {
      this.snackBar.open('Source model ID and output model ID are required', 'Close', { duration: 3000 });
      return;
    }

    this.savingCompiledGraph = true;
    this.saveResult = null;

    const targetOutputs = this.saveTargetOutputs
      ? this.saveTargetOutputs.split(',').map(s => s.trim()).filter(s => s.length > 0)
      : undefined;

    const request: SaveCompiledGraphRequest = {
      sourceModelId: this.saveSourceModelId,
      outputModelId: this.saveOutputModelId,
      targetOutputs: targetOutputs,
      selectedPasses: this.saveSelectedPasses.size > 0 ? Array.from(this.saveSelectedPasses) : undefined,
      profile: this.saveProfile || undefined,
      description: this.saveDescription || undefined
    };

    const sub = this.compilerService.saveCompiledGraph(request).subscribe({
      next: (result) => {
        this.saveResult = result;
        this.savingCompiledGraph = false;
        if (result.success) {
          this.snackBar.open(`Compiled graph saved as "${result.outputModelId}"`, 'Close', { duration: 3000 });
          this.loadCompiledModels();
        } else {
          this.snackBar.open(`Save failed: ${result.error}`, 'Close', { duration: 5000 });
        }
      },
      error: (err) => {
        this.savingCompiledGraph = false;
        this.saveResult = {
          success: false,
          outputModelId: '',
          outputPath: '',
          error: err.message,
          beforeOpsCount: 0,
          afterOpsCount: 0,
          beforeVarsCount: 0,
          afterVarsCount: 0,
          sizeBeforeBytes: 0,
          sizeAfterBytes: 0,
          reductionPercent: 0,
          optimizationTimeMs: 0,
          passesApplied: []
        };
        this.snackBar.open(`Save failed: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
  }

  inspectCompiledModel(modelId: string): void {
    this.inspectorModelId = modelId;
    this.activeTab = 1; // Switch to Graph Inspector tab
    this.inspectGraph();
  }

  isSavePassSelected(passId: string): boolean {
    return this.saveSelectedPasses.has(passId);
  }

  toggleSavePass(passId: string): void {
    if (this.saveSelectedPasses.has(passId)) {
      this.saveSelectedPasses.delete(passId);
    } else {
      this.saveSelectedPasses.add(passId);
    }
    this.saveProfile = '';
  }

  selectSaveProfile(profileName: string): void {
    this.saveProfile = profileName;
    this.saveSelectedPasses.clear();
    const profile = this.profiles.find(p => p.profileName === profileName);
    if (profile) {
      profile.includedPasses.forEach(p => this.saveSelectedPasses.add(p));
    }
  }

  // ==================== Utility Methods ====================

  formatBytes(bytes: number | undefined): string {
    if (!bytes) return 'N/A';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }

  formatDuration(ms: number | undefined): string {
    if (!ms) return 'N/A';
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    return `${(ms / 60000).toFixed(1)}m`;
  }
}
