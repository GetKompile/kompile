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

import { Component, OnInit, OnDestroy, Input, Output, EventEmitter, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSliderModule } from '@angular/material/slider';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatExpansionModule } from '@angular/material/expansion';
import { Subscription, interval } from 'rxjs';
import { startWith, switchMap } from 'rxjs/operators';

import {
  PipelineConfig,
  PipelinePreset,
  PipelinePresetInfo,
  PipelinePresets,
  StageMetrics,
  StageMetricsResponse,
  LoaderInfo,
  AVAILABLE_TOKENIZERS,
  TokenizerOption
} from '../../../models/api-models';
import { ProcessingSettingsService } from '../../../services/processing-settings.service';
import { GraphExtractionService, GraphExtractionConfig, SchemaMode, ModelProvider, ModelInfo } from '../../../services/graph-extraction.service';

@Component({
  selector: 'app-pipeline-settings-panel',
  templateUrl: './pipeline-settings-panel.component.html',
  styleUrls: ['./pipeline-settings-panel.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatSliderModule,
    MatSelectModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    MatTooltipModule,
    MatChipsModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatExpansionModule
  ]
})
export class PipelineSettingsPanelComponent implements OnInit, OnDestroy {
  @Input() showAdvanced = false;
  @Input() availableLoaders: LoaderInfo[] = [];
  @Output() settingsChanged = new EventEmitter<PipelineConfig>();

  // Pipeline configuration
  config: PipelineConfig | null = null;
  presets: PipelinePresets | null = null;
  selectedPreset: PipelinePreset = 'adaptive';

  // Graph extraction configuration (loaded from separate API)
  graphConfig: GraphExtractionConfig | null = null;
  schemaModes: SchemaMode[] = [];
  suggestedEntityTypes: string[] = [];
  suggestedRelationshipTypes: string[] = [];
  modelProviders: ModelProvider[] = [];

  // Stage metrics (for real-time monitoring)
  stageMetrics: StageMetricsResponse | null = null;

  // Available options
  tokenizers: TokenizerOption[] = AVAILABLE_TOKENIZERS;

  // UI state
  isLoading = true;
  isExpanded = true; // Show stage configuration expanded by default
  errorMessage: string | null = null;
  graphConfigSaving = false;

  private subscriptions = new Subscription();
  private pollInterval = 5000; // 5 seconds

  constructor(
    private processingSettingsService: ProcessingSettingsService,
    private graphExtractionService: GraphExtractionService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadConfiguration();
    this.loadPresets();
    this.loadGraphConfig();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  private loadConfiguration(): void {
    this.isLoading = true;
    this.subscriptions.add(
      this.processingSettingsService.getPipelineConfig().subscribe({
        next: (config: PipelineConfig) => {
          this.config = config;
          this.isLoading = false;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          console.error('Failed to load pipeline config:', err);
          this.errorMessage = 'Failed to load pipeline configuration';
          this.isLoading = false;
          this.cdr.markForCheck();
        }
      })
    );
  }

  private loadPresets(): void {
    this.subscriptions.add(
      this.processingSettingsService.getPipelinePresets().subscribe({
        next: (presets: PipelinePresets) => {
          this.presets = presets;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          console.error('Failed to load pipeline presets:', err);
        }
      })
    );
  }

  private loadGraphConfig(): void {
    // Load graph extraction config, schema modes, and suggested types in parallel
    this.subscriptions.add(
      this.graphExtractionService.getConfig().subscribe({
        next: (config: GraphExtractionConfig) => {
          this.graphConfig = config;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          console.error('Failed to load graph extraction config:', err);
        }
      })
    );

    this.subscriptions.add(
      this.graphExtractionService.getSchemaModes().subscribe({
        next: (modes: SchemaMode[]) => {
          this.schemaModes = modes;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          console.error('Failed to load schema modes:', err);
        }
      })
    );

    this.subscriptions.add(
      this.graphExtractionService.getSuggestedEntityTypes().subscribe({
        next: (types: string[]) => {
          this.suggestedEntityTypes = types;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          console.error('Failed to load suggested entity types:', err);
        }
      })
    );

    this.subscriptions.add(
      this.graphExtractionService.getSuggestedRelationshipTypes().subscribe({
        next: (types: string[]) => {
          this.suggestedRelationshipTypes = types;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          console.error('Failed to load suggested relationship types:', err);
        }
      })
    );

    this.subscriptions.add(
      this.graphExtractionService.getModelProviders().subscribe({
        next: (providers: ModelProvider[]) => {
          this.modelProviders = providers;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          console.error('Failed to load model providers:', err);
        }
      })
    );
  }

  /**
   * Handle model provider change.
   */
  onModelProviderChange(providerId: string): void {
    this.onGraphConfigChange('extractionModelProvider', providerId);
    // Clear the model name when provider changes
    if (this.graphConfig) {
      this.graphConfig.extractionModelProvider = providerId;
      this.graphConfig.extractionModelName = undefined;
    }
  }

  /**
   * Get the currently selected provider.
   */
  getSelectedProvider(): ModelProvider | null {
    if (!this.graphConfig?.extractionModelProvider) return null;
    return this.modelProviders.find(p => p.id === this.graphConfig?.extractionModelProvider) || null;
  }

  /**
   * Get available models for the selected provider.
   */
  getAvailableModels(): ModelInfo[] {
    const provider = this.getSelectedProvider();
    return provider?.models || [];
  }

  /**
   * Check if the selected provider has models available.
   */
  hasModelsAvailable(): boolean {
    return this.getAvailableModels().length > 0;
  }

  onGraphConfigChange(field: string, value: unknown): void {
    if (!this.graphConfig) return;

    const update: Partial<GraphExtractionConfig> = { [field]: value };
    this.graphConfigSaving = true;
    this.cdr.markForCheck();

    this.subscriptions.add(
      this.graphExtractionService.patchConfig(update).subscribe({
        next: (config: GraphExtractionConfig) => {
          this.graphConfig = config;
          this.graphConfigSaving = false;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          console.error('Failed to update graph config:', err);
          this.graphConfigSaving = false;
          this.cdr.markForCheck();
        }
      })
    );
  }

  toggleGraphExtraction(): void {
    this.graphConfigSaving = true;
    this.cdr.markForCheck();

    this.subscriptions.add(
      this.graphExtractionService.toggleEnabled().subscribe({
        next: (config: GraphExtractionConfig) => {
          this.graphConfig = config;
          this.graphConfigSaving = false;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          console.error('Failed to toggle graph extraction:', err);
          this.graphConfigSaving = false;
          this.cdr.markForCheck();
        }
      })
    );
  }

  resetGraphConfig(): void {
    this.graphConfigSaving = true;
    this.cdr.markForCheck();

    this.subscriptions.add(
      this.graphExtractionService.resetConfig().subscribe({
        next: (config: GraphExtractionConfig) => {
          this.graphConfig = config;
          this.graphConfigSaving = false;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          console.error('Failed to reset graph config:', err);
          this.graphConfigSaving = false;
          this.cdr.markForCheck();
        }
      })
    );
  }

  startMetricsPolling(): void {
    this.subscriptions.add(
      interval(this.pollInterval).pipe(
        startWith(0),
        switchMap(() => this.processingSettingsService.getStageMetrics())
      ).subscribe({
        next: (metrics: StageMetricsResponse) => {
          this.stageMetrics = metrics;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          console.error('Failed to load stage metrics:', err);
        }
      })
    );
  }

  onPresetChange(event: { value: string }): void {
    const preset = event.value as PipelinePreset;
    this.applyPreset(preset);
  }

  applyPreset(preset: PipelinePreset): void {
    this.selectedPreset = preset;
    // In a full implementation, this would update the config based on preset
    this.cdr.markForCheck();
  }

  getPresetDescription(preset: PipelinePreset): string {
    if (!this.presets) return '';
    return this.getPresetInfoByName(preset)?.description || '';
  }

  getPresetInfo(preset: PipelinePreset): PipelinePresetInfo | null {
    if (!this.presets) return null;
    return this.getPresetInfoByName(preset);
  }

  private getPresetInfoByName(preset: PipelinePreset): PipelinePresetInfo | null {
    if (!this.presets) return null;
    switch (preset) {
      case 'adaptive': return this.presets.adaptive;
      case 'memoryOptimized': return this.presets.memoryOptimized;
      case 'highThroughput': return this.presets.highThroughput;
      case 'custom': return null; // Custom preset doesn't have preset info
      default: return null;
    }
  }

  onSettingChange(): void {
    if (this.config) {
      this.settingsChanged.emit(this.config);
    }
  }

  toggleExpanded(): void {
    this.isExpanded = !this.isExpanded;
    if (this.isExpanded) {
      this.startMetricsPolling();
    }
    this.cdr.markForCheck();
  }

  // Helper methods for display
  formatMemory(mb: number): string {
    if (mb >= 1024) {
      return `${(mb / 1024).toFixed(1)} GB`;
    }
    return `${mb} MB`;
  }

  getMemoryUsagePercent(): number {
    if (!this.config?.system) return 0;
    return Math.round((this.config.system.usedMemoryMB / this.config.system.maxMemoryMB) * 100);
  }

  getMemoryStatusClass(): string {
    const percent = this.getMemoryUsagePercent();
    if (percent >= 90) return 'memory-critical';
    if (percent >= 70) return 'memory-warning';
    return 'memory-ok';
  }

  getStageStatusClass(stage: StageMetrics): string {
    if (stage.itemsFailed > 0) return 'stage-error';
    if (stage.itemsProcessed > 0) return 'stage-active';
    return 'stage-idle';
  }

  getStageIcon(stageName: string): string {
    const icons: { [key: string]: string } = {
      'extraction': 'folder_open',
      'tokenization': 'text_fields',
      'chunking': 'content_cut',
      'embedding': 'memory',
      'indexing': 'storage',
      'graph-building': 'hub'
    };
    return icons[stageName] || 'settings';
  }

  formatThroughput(value: number): string {
    if (value >= 1000) {
      return `${(value / 1000).toFixed(1)}K/s`;
    }
    return `${value.toFixed(1)}/s`;
  }

  // Helper to get stage metrics by name with proper typing
  getStageMetricsByName(stageName: string): StageMetrics | null {
    if (!this.stageMetrics) return null;
    switch (stageName) {
      case 'extraction': return this.stageMetrics.extraction;
      case 'tokenization': return this.stageMetrics.tokenization;
      case 'chunking': return this.stageMetrics.chunking;
      case 'embedding': return this.stageMetrics.embedding;
      case 'indexing': return this.stageMetrics.indexing;
      case 'graph-building': return this.stageMetrics.graphBuilding || null;
      default: return null;
    }
  }

  // Stage names for iteration
  readonly stageNames = ['extraction', 'tokenization', 'chunking', 'embedding', 'indexing', 'graph-building'];

  /**
   * Check if a specific RAM tier is the current system's tier.
   */
  isCurrentRamTier(tier: 'lt4' | '4to8' | '8to16' | 'gt16'): boolean {
    if (!this.config?.system?.maxMemoryMB) {
      return false;
    }
    const ramGb = this.config.system.maxMemoryMB / 1024;
    switch (tier) {
      case 'lt4': return ramGb < 4;
      case '4to8': return ramGb >= 4 && ramGb < 8;
      case '8to16': return ramGb >= 8 && ramGb < 16;
      case 'gt16': return ramGb >= 16;
      default: return false;
    }
  }

  /**
   * Get system RAM in GB for display.
   */
  getSystemRamGb(): string {
    if (!this.config?.system?.maxMemoryMB) {
      return '?';
    }
    return (this.config.system.maxMemoryMB / 1024).toFixed(1);
  }
}
