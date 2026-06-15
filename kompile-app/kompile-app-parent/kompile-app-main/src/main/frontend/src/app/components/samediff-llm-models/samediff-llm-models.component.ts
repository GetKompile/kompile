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

import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSliderModule } from '@angular/material/slider';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { SameDiffLLMService } from '../../services/samediff-llm.service';
import { SameDiffLlmPipelineService } from '../../services/samediff-llm-pipeline.service';
import {
  SameDiffLLMModelSet,
  SameDiffLLMPipelineStage,
  SameDiffLLMPreset,
  SameDiffLLMDownloadStatus,
  SameDiffLLMModelSetsStatus,
  SameDiffLLMServiceStatus
} from '../../models/samediff-llm-models';
import {
  PipelineListItem,
  StageListItem,
  LlmRegistryStats,
  LlmGenerationPreset,
  LlmPipelineDefinition,
  createEmptyPipeline
} from '../../models/samediff-llm-pipeline-models';

@Component({
  selector: 'app-samediff-llm-models',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSliderModule,
    MatCheckboxModule,
    MatChipsModule,
    MatSnackBarModule
  ],
  templateUrl: './samediff-llm-models.component.html',
  styleUrls: ['./samediff-llm-models.component.css']
})
export class SameDiffLLMModelsComponent implements OnInit, OnDestroy {

  // State
  isLoading = false;
  modelSets: SameDiffLLMModelSet[] = [];
  pipelineStages: SameDiffLLMPipelineStage[] = [];
  presets: SameDiffLLMPreset[] = [];
  serviceStatus: SameDiffLLMServiceStatus | null = null;
  modelSetsStatus: SameDiffLLMModelSetsStatus | null = null;

  // Download state
  downloadingSetId: string | null = null;
  downloadProgress: SameDiffLLMDownloadStatus | null = null;

  // UI state
  expandedSetId: string | null = null;
  selectedPresetId: string | null = null;
  showPipelineStages = false;
  activeTab: 'models' | 'pipelines' | 'config' = 'models';

  // Pipeline builder state
  showPipelineBuilder = false;
  newPipelineId = '';
  selectedModelSetId = '';
  maxNewTokens = 512;
  temperature = 0.7;
  topK = 40;
  enableToolCalling = false;

  // Pipeline config state (parity with VLM)
  configPipelines: PipelineListItem[] = [];
  configStages: StageListItem[] = [];
  registryStats: LlmRegistryStats | null = null;
  generationPresets: LlmGenerationPreset[] = [];
  selectedConfigPipelineId: string | null = null;
  selectedConfigPipeline: LlmPipelineDefinition | null = null;
  showConfigPipelineEditor = false;
  editingConfigPipeline: LlmPipelineDefinition = createEmptyPipeline();

  private destroy$ = new Subject<void>();

  constructor(
    private samediffLlmService: SameDiffLLMService,
    private pipelineConfigService: SameDiffLlmPipelineService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadAllData();

    this.samediffLlmService.downloadEvents$
      .pipe(takeUntil(this.destroy$))
      .subscribe(status => {
        this.downloadProgress = status;
        if (status.complete) {
          this.downloadingSetId = null;
          this.loadModelSetsStatus();
        }
        this.cdr.detectChanges();
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadAllData(): void {
    this.isLoading = true;

    Promise.all([
      this.loadModelSets(),
      this.loadModelSetsStatus(),
      this.loadPipelineStages(),
      this.loadPresets(),
      this.loadServiceStatus(),
      this.loadConfigPipelines(),
      this.loadConfigStages(),
      this.loadRegistryStats(),
      this.loadGenerationPresets()
    ]).finally(() => {
      this.isLoading = false;
      this.cdr.detectChanges();
    });
  }

  // ==================== Data Loading ====================

  private loadModelSets(): Promise<void> {
    return new Promise(resolve => {
      this.samediffLlmService.getModelSets()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: sets => { this.modelSets = sets; resolve(); },
          error: () => resolve()
        });
    });
  }

  private loadModelSetsStatus(): Promise<void> {
    return new Promise(resolve => {
      this.samediffLlmService.getModelSetsStatus()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: status => {
            this.modelSetsStatus = status;
            this.modelSets = this.modelSets.map(set => ({
              ...set,
              cached: status.modelSets[set.setId] || false
            }));
            resolve();
          },
          error: () => resolve()
        });
    });
  }

  private loadPipelineStages(): Promise<void> {
    return new Promise(resolve => {
      this.samediffLlmService.getPipelineStages()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: stages => { this.pipelineStages = stages; resolve(); },
          error: () => resolve()
        });
    });
  }

  private loadPresets(): Promise<void> {
    return new Promise(resolve => {
      this.samediffLlmService.getPresets()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: presets => { this.presets = presets; resolve(); },
          error: () => resolve()
        });
    });
  }

  private loadServiceStatus(): Promise<void> {
    return new Promise(resolve => {
      this.samediffLlmService.getServiceStatus()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: status => { this.serviceStatus = status; resolve(); },
          error: () => resolve()
        });
    });
  }

  private loadConfigPipelines(): Promise<void> {
    return new Promise(resolve => {
      this.pipelineConfigService.listPipelines()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: response => { this.configPipelines = response.pipelines; resolve(); },
          error: () => resolve()
        });
    });
  }

  private loadConfigStages(): Promise<void> {
    return new Promise(resolve => {
      this.pipelineConfigService.listStages()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: response => { this.configStages = response.stages; resolve(); },
          error: () => resolve()
        });
    });
  }

  private loadRegistryStats(): Promise<void> {
    return new Promise(resolve => {
      this.pipelineConfigService.getRegistryStats()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: stats => { this.registryStats = stats; resolve(); },
          error: () => resolve()
        });
    });
  }

  private loadGenerationPresets(): Promise<void> {
    return new Promise(resolve => {
      this.pipelineConfigService.getPresets()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: presets => { this.generationPresets = presets; resolve(); },
          error: () => resolve()
        });
    });
  }

  // ==================== Model Set Operations ====================

  downloadModelSet(setId: string): void {
    if (this.downloadingSetId) {
      this.showSnackbar('A download is already in progress', true);
      return;
    }

    this.downloadingSetId = setId;
    this.downloadProgress = null;

    this.samediffLlmService.downloadModelSetWithProgress(setId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: status => {
          this.downloadProgress = status;
          if (status.complete) {
            if (status.success) {
              this.showSnackbar(`Model set "${setId}" downloaded successfully`);
            } else {
              this.showSnackbar(`Download failed: ${status.message}`, true);
            }
            this.downloadingSetId = null;
            this.loadModelSetsStatus();
          }
          this.cdr.detectChanges();
        },
        error: err => {
          this.downloadingSetId = null;
          this.showSnackbar(`Download failed: ${err.message}`, true);
          this.cdr.detectChanges();
        }
      });
  }

  deleteModelSet(setId: string): void {
    if (!confirm(`Are you sure you want to delete the cached model "${setId}"?`)) {
      return;
    }

    this.samediffLlmService.deleteModelSet(setId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          if (response.success) {
            this.showSnackbar(`Model set "${setId}" deleted`);
            this.loadModelSetsStatus();
          } else {
            this.showSnackbar(`Delete failed: ${response.error}`, true);
          }
        },
        error: err => this.showSnackbar(`Delete failed: ${err.message}`, true)
      });
  }

  // ==================== Pipeline Builder ====================

  togglePipelineBuilder(): void {
    this.showPipelineBuilder = !this.showPipelineBuilder;
  }

  createPipeline(): void {
    if (!this.newPipelineId) {
      this.showSnackbar('Pipeline ID is required', true);
      return;
    }
    if (!this.selectedModelSetId) {
      this.showSnackbar('Model set selection is required', true);
      return;
    }

    const config = {
      pipelineId: this.newPipelineId,
      modelSetId: this.selectedModelSetId,
      maxNewTokens: this.maxNewTokens,
      temperature: this.temperature,
      topK: this.topK,
      enableToolCalling: this.enableToolCalling
    };

    this.samediffLlmService.createPipeline(config)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.showSnackbar(`Pipeline "${this.newPipelineId}" created successfully`);
          this.showPipelineBuilder = false;
          this.newPipelineId = '';
          this.selectedModelSetId = '';
        },
        error: err => this.showSnackbar(`Failed to create pipeline: ${err.message}`, true)
      });
  }

  // ==================== Config Pipeline Operations ====================

  selectConfigPipeline(pipelineId: string): void {
    this.selectedConfigPipelineId = pipelineId;
    this.pipelineConfigService.getPipeline(pipelineId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: pipeline => {
          this.selectedConfigPipeline = pipeline;
          this.cdr.detectChanges();
        },
        error: err => this.showSnackbar(`Failed to load pipeline: ${err.message}`, true)
      });
  }

  deleteConfigPipeline(pipelineId: string): void {
    if (!confirm(`Delete pipeline "${pipelineId}"?`)) return;

    this.pipelineConfigService.deletePipeline(pipelineId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.showSnackbar(`Pipeline "${pipelineId}" deleted`);
          this.selectedConfigPipelineId = null;
          this.selectedConfigPipeline = null;
          this.loadConfigPipelines();
          this.loadRegistryStats();
        },
        error: err => this.showSnackbar(`Delete failed: ${err.message}`, true)
      });
  }

  openConfigPipelineEditor(pipeline?: LlmPipelineDefinition): void {
    if (pipeline) {
      this.editingConfigPipeline = { ...pipeline };
    } else {
      this.editingConfigPipeline = createEmptyPipeline();
    }
    this.showConfigPipelineEditor = true;
  }

  closeConfigPipelineEditor(): void {
    this.showConfigPipelineEditor = false;
  }

  saveConfigPipeline(): void {
    const pipeline = this.editingConfigPipeline;
    if (!pipeline.pipelineId) {
      this.showSnackbar('Pipeline ID is required', true);
      return;
    }

    const isUpdate = this.configPipelines.some(p => p.pipelineId === pipeline.pipelineId);
    const observable = isUpdate
      ? this.pipelineConfigService.updatePipeline(pipeline.pipelineId, pipeline)
      : this.pipelineConfigService.createPipeline(pipeline);

    observable.pipe(takeUntil(this.destroy$)).subscribe({
      next: () => {
        this.showSnackbar(`Pipeline "${pipeline.pipelineId}" ${isUpdate ? 'updated' : 'created'}`);
        this.showConfigPipelineEditor = false;
        this.loadConfigPipelines();
        this.loadRegistryStats();
      },
      error: err => this.showSnackbar(`Save failed: ${err.message}`, true)
    });
  }

  // ==================== Tab Navigation ====================

  setActiveTab(tab: 'models' | 'pipelines' | 'config'): void {
    this.activeTab = tab;
  }

  // ==================== UI Helpers ====================

  refreshData(): void {
    this.loadAllData();
    this.showSnackbar('Data refreshed');
  }

  toggleSetExpansion(setId: string): void {
    this.expandedSetId = this.expandedSetId === setId ? null : setId;
  }

  isSetExpanded(setId: string): boolean {
    return this.expandedSetId === setId;
  }

  togglePipelineStages(): void {
    this.showPipelineStages = !this.showPipelineStages;
  }

  getModelSetIcon(setId: string): string {
    if (setId.includes('smollm')) return 'psychology';
    if (setId.includes('phi')) return 'school';
    if (setId.includes('gemma')) return 'auto_awesome';
    if (setId.includes('llama')) return 'pets';
    return 'memory';
  }

  getPipelineStageIcon(stage: { id?: string; stageId?: string }): string {
    const id = stage.id || stage.stageId || '';
    if (id.includes('TOKENIZ')) return 'text_fields';
    if (id.includes('EMBED')) return 'data_array';
    if (id.includes('DECODER') || id.includes('DECODING')) return 'autorenew';
    if (id.includes('SAMPL')) return 'casino';
    if (id.includes('DECODE') || id.includes('TOKEN_DECODING')) return 'translate';
    return 'settings';
  }

  getComponentStageIcon(role: string): string {
    return this.getPipelineStageIcon({ id: role });
  }

  getPresetIcon(presetId: string): string {
    switch (presetId) {
      case 'greedy': return 'bolt';
      case 'chat': return 'chat';
      case 'creative': return 'auto_awesome';
      case 'code': return 'code';
      case 'tool-calling': return 'build';
      case 'comprehensive': return 'all_inclusive';
      default: return 'settings';
    }
  }

  getCachedModelCount(): number {
    if (!this.modelSetsStatus?.modelSets) return 0;
    return Object.values(this.modelSetsStatus.modelSets).filter(cached => cached).length;
  }

  getTotalModelCount(): number {
    return this.modelSets.length;
  }

  isModelSetCached(setId: string): boolean {
    return this.modelSetsStatus?.modelSets?.[setId] || false;
  }

  isDownloading(setId: string): boolean {
    return this.downloadingSetId === setId;
  }

  getDownloadProgress(): number {
    if (!this.downloadProgress) return 0;
    return (this.downloadProgress.componentProgress || 0) * 100;
  }

  formatComponentCount(count: number): string {
    return count === 1 ? '1 component' : `${count} components`;
  }

  formatModelSize(hiddenSize: number): string {
    if (hiddenSize >= 1000000000) return (hiddenSize / 1000000000).toFixed(1) + 'B';
    if (hiddenSize >= 1000000) return (hiddenSize / 1000000).toFixed(1) + 'M';
    return (hiddenSize / 1000).toFixed(1) + 'K';
  }

  ensurePresetModels(presetId: string): void {
    const preset = this.generationPresets.find(p => p.id === presetId);
    if (preset) {
      this.showSnackbar(`Applying preset: ${preset.name}`);
      this.maxNewTokens = preset.maxNewTokens;
      this.temperature = preset.temperature;
      this.topK = preset.topK;
      this.enableToolCalling = preset.enableToolCalling;
    }
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }
}
