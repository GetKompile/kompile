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

import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';
import { StagingService } from '../../services/staging.service';
import { CompilerService, GraphInfoResponse } from '../../services/compiler.service';
import { CatalogModel, ModelCatalog, AutoOptimizationConfigDto, getModelTypeIcon, resolvePassDetail, getCategoryColor, OptimizationPassDetail } from '../../models/api-models';
import { CatalogModelInfoDialogComponent } from '../catalog-model-info-dialog/catalog-model-info-dialog.component';
import { OptimizeDialogComponent, OptimizeDialogResult } from '../optimize-dialog/optimize-dialog.component';
import { ModelGraphDialogComponent } from '../model-graph-visualizer/model-graph-visualizer.component';

@Component({
  selector: 'app-model-catalog',
  standalone: false,
  templateUrl: './model-catalog.component.html',
  styleUrls: ['./model-catalog.component.css']
})
export class ModelCatalogComponent implements OnInit {

  catalog: ModelCatalog | null = null;
  isLoading = true;
  error: string | null = null;

  // Track which models are being staged or optimized
  stagingModels: Set<string> = new Set();
  optimizingModels: Set<string> = new Set();

  // Cache of graph info for installed models
  graphInfoCache: { [modelId: string]: { inputNames: string[]; outputNames: string[] } } = {};
  fullGraphInfoCache: { [modelId: string]: GraphInfoResponse } = {};
  dspPlanCache: { [modelId: string]: CompactPlanStage[] } = {};

  // Auto-optimization settings
  autoOptimizeEnabled = false;
  autoOptimizeSettingsExpanded = false;
  autoOptimizeConfig: AutoOptimizationConfigDto = {
    enabledPasses: [
      'UnusedFunctionOptimizations', 'ConstantFunctionOptimizations',
      'IdentityFunctionOptimizations', 'ShapeFunctionOptimizations',
      'LinearFusionOptimizations', 'AttentionFusionOptimizations'
    ],
    preset: 'default',
    quantizationType: undefined,
    quantizePerChannel: false,
    maxIterations: 3
  };

  availablePresets = [
    { id: 'default', name: 'Default', description: 'Standard optimization pipeline' },
    { id: 'transformer', name: 'Transformer', description: 'Optimized for transformer architectures' },
    { id: 'minimal', name: 'Minimal (Safe)', description: 'Conservative optimizations only' },
    { id: 'aggressive', name: 'Aggressive', description: 'All optimizations including GPU' }
  ];

  constructor(
    private stagingService: StagingService,
    private compilerService: CompilerService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadCatalog();
    this.loadAutoOptimizeConfig();
  }

  loadCatalog(): void {
    this.isLoading = true;
    this.error = null;

    this.stagingService.getCatalog().subscribe({
      next: (catalog) => {
        this.catalog = catalog;
        this.isLoading = false;
        this.cdr.detectChanges();
        this.loadGraphInfoForInstalledModels();
      },
      error: (err) => {
        this.error = err.message;
        this.isLoading = false;
        this.cdr.detectChanges();
      }
    });
  }

  loadAutoOptimizeConfig(): void {
    this.stagingService.getAutoOptimizeConfig().subscribe({
      next: (response) => {
        this.autoOptimizeEnabled = response.enabled;
        if (response.config) {
          this.autoOptimizeConfig = {
            enabledPasses: response.config.enabledPasses || this.autoOptimizeConfig.enabledPasses,
            preset: response.config.preset || 'default',
            quantizationType: response.config.quantizationType,
            quantizePerChannel: response.config.quantizePerChannel || false,
            maxIterations: response.config.maxIterations || 3
          };
        }
        this.cdr.detectChanges();
      },
      error: () => {} // silently ignore
    });
  }

  toggleAutoOptimize(): void {
    this.autoOptimizeEnabled = !this.autoOptimizeEnabled;
    if (this.autoOptimizeEnabled) {
      this.saveAutoOptimizeConfig();
    } else {
      this.stagingService.clearAutoOptimizeConfig().subscribe({
        next: () => this.showSnackbar('Auto-optimization disabled'),
        error: (err) => this.showSnackbar('Failed to update config: ' + err.message, true)
      });
    }
  }

  saveAutoOptimizeConfig(): void {
    this.stagingService.setAutoOptimizeConfig(this.autoOptimizeConfig).subscribe({
      next: () => this.showSnackbar('Auto-optimization config saved'),
      error: (err) => this.showSnackbar('Failed to save config: ' + err.message, true)
    });
  }

  onPresetChange(presetId: string): void {
    this.autoOptimizeConfig.preset = presetId;
    switch (presetId) {
      case 'default':
      case 'transformer':
        this.autoOptimizeConfig.enabledPasses = [
          'UnusedFunctionOptimizations', 'ConstantFunctionOptimizations',
          'IdentityFunctionOptimizations', 'ShapeFunctionOptimizations',
          'LinearFusionOptimizations', 'AttentionFusionOptimizations'
        ];
        break;
      case 'minimal':
        this.autoOptimizeConfig.enabledPasses = [
          'UnusedFunctionOptimizations', 'IdentityFunctionOptimizations'
        ];
        break;
      case 'aggressive':
        this.autoOptimizeConfig.enabledPasses = [
          'UnusedFunctionOptimizations', 'ConstantFunctionOptimizations',
          'IdentityFunctionOptimizations', 'ShapeFunctionOptimizations',
          'LinearFusionOptimizations', 'AttentionFusionOptimizations',
          'CuDNNFunctionOptimizations'
        ];
        break;
    }
    if (this.autoOptimizeEnabled) {
      this.saveAutoOptimizeConfig();
    }
  }

  stageModel(model: CatalogModel, autoPromote: boolean = false): void {
    if (this.stagingModels.has(model.id)) {
      return;
    }

    this.stagingModels.add(model.id);

    this.stagingService.stageFromCatalog(model.id, autoPromote).subscribe({
      next: (stagingInfo) => {
        this.stagingModels.delete(model.id);
        this.showSnackbar(`Started staging ${model.id}`);
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.stagingModels.delete(model.id);
        this.showSnackbar(`Failed to stage ${model.id}: ${err.message}`, true);
        this.cdr.detectChanges();
      }
    });
  }

  isStaging(modelId: string): boolean {
    return this.stagingModels.has(modelId);
  }

  getOptimizationSummary(model: CatalogModel): string {
    const meta = (model as any).metadata;
    if (!meta?.optimized) return '';
    const stats = meta.optimization_stats;
    if (stats?.reduction_percent) {
      return `${stats.reduction_percent.toFixed(1)}% smaller`;
    }
    return 'Optimized';
  }

  getEncoders(): CatalogModel[] {
    return this.catalog?.encoders || [];
  }

  getCrossEncoders(): CatalogModel[] {
    return this.catalog?.crossEncoders || [];
  }

  getVlmModels(): CatalogModel[] {
    return this.catalog?.vlm || [];
  }

  /**
   * Check if a model can be optimized. The backend sets `optimizable=true` when
   * a SameDiff .fb/.sdz file exists on disk — regardless of the model's declared
   * format (e.g. an ONNX model with a converted .sdz equivalent is optimizable).
   */
  canOptimize(model: CatalogModel): boolean {
    return !!model.optimizable;
  }

  getModelTypeIcon = getModelTypeIcon;

  getSourceIcon(source: string): string {
    switch (source.toLowerCase()) {
      case 'huggingface':
        return 'hub';
      case 'github':
        return 'code';
      default:
        return 'cloud';
    }
  }

  optimizeModel(model: CatalogModel): void {
    if (this.optimizingModels.has(model.id)) return;

    const dialogRef = this.dialog.open(OptimizeDialogComponent, {
      data: { modelId: model.id, isReoptimize: !!model.metadata?.optimized },
      width: '640px',
      maxHeight: '85vh'
    });

    dialogRef.afterClosed().subscribe((result: OptimizeDialogResult | null) => {
      if (!result) return; // cancelled

      this.optimizingModels.add(model.id);

      const request = {
        modelId: model.id,
        preset: result.preset,
        enabledOptimizations: result.selectedPasses,
        createBackup: true,
        force: !!model.metadata?.optimized,
        maxIterations: result.maxIterations
      };

      this.stagingService.optimizeModelConfigurable(model.id, request).subscribe({
        next: (optimizeResult: any) => {
          this.optimizingModels.delete(model.id);
          if (optimizeResult.success) {
            this.showSnackbar(`Optimized ${model.id} successfully`);
            this.loadCatalog();
          } else {
            this.showSnackbar(`Optimization failed: ${optimizeResult.error || optimizeResult.message}`, true);
          }
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.optimizingModels.delete(model.id);
          this.showSnackbar(`Failed to optimize ${model.id}: ${err.message}`, true);
          this.cdr.detectChanges();
        }
      });
    });
  }

  isOptimizing(modelId: string): boolean {
    return this.optimizingModels.has(modelId);
  }

  formatOptTime(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  }

  resolvePass = resolvePassDetail;
  getCategoryColor = getCategoryColor;

  getResolvedPasses(model: CatalogModel): OptimizationPassDetail[] {
    const passes = model.metadata?.applied_optimizations || [];
    return passes.map(p => resolvePassDetail(p));
  }

  showGraph(modelId: string): void {
    this.dialog.open(ModelGraphDialogComponent, {
      data: { modelId },
      width: '90vw',
      maxWidth: '1400px',
      height: '85vh',
      panelClass: 'graph-dialog-panel'
    });
  }

  showModelInfo(model: CatalogModel): void {
    this.dialog.open(CatalogModelInfoDialogComponent, {
      data: model,
      width: '720px',
      maxHeight: '85vh'
    });
  }

  isOcrModel(model: CatalogModel): boolean {
    const t = model.modelType;
    return t === 'ocr_detection' || t === 'ocr_recognition' || t === 'ocr_table' || t === 'ocr_pipeline';
  }

  isVlmModel(model: CatalogModel): boolean {
    return model.modelType === 'vlm_pipeline';
  }

  private loadGraphInfoForInstalledModels(): void {
    if (!this.catalog) return;
    const allModels = [
      ...(this.catalog.encoders || []),
      ...(this.catalog.crossEncoders || []),
      ...(this.catalog.vlm || [])
    ];
    for (const model of allModels) {
      if (model.installed && model.optimizable && !this.graphInfoCache[model.id]) {
        this.compilerService.getGraphInfo(model.id).subscribe({
          next: (info: GraphInfoResponse) => {
            this.graphInfoCache[model.id] = {
              inputNames: info.inputNames || [],
              outputNames: info.outputNames || []
            };
            this.fullGraphInfoCache[model.id] = info;
            this.dspPlanCache[model.id] = this.buildCompactPlan(info);
            this.cdr.detectChanges();
          },
          error: () => {} // silently ignore - graph info is supplemental
        });
      }
    }
  }

  buildCompactPlan(info: GraphInfoResponse): CompactPlanStage[] {
    const stages: CompactPlanStage[] = [];

    // Input stage
    stages.push({
      label: 'Input',
      icon: 'input',
      color: '#2196f3',
      detail: info.inputNames.length + ' tensors'
    });

    // Layer groups or op summary
    const analysis = info.analysis;
    if (analysis?.layerGroups && analysis.layerGroups.length > 0) {
      for (const group of analysis.layerGroups) {
        stages.push({
          label: group.name,
          icon: this.getPlanStageIcon(group.opTypes),
          color: '#7c4dff',
          detail: group.count + ' layers'
        });
      }
    } else {
      // Summarize by dominant op type
      const entries = Object.entries(info.opTypes).sort((a, b) => b[1] - a[1]);
      const topOps = entries.slice(0, 3);
      if (topOps.length > 0) {
        stages.push({
          label: 'Compute',
          icon: 'memory',
          color: '#7c4dff',
          detail: info.totalOps + ' ops'
        });
      }
    }

    // Fusion indicator
    if (analysis && analysis.fusedOpCount > 0) {
      stages.push({
        label: 'Fused',
        icon: 'bolt',
        color: '#ff9800',
        detail: analysis.fusedOpCount + ' fused'
      });
    }

    // Output stage
    stages.push({
      label: 'Output',
      icon: 'output',
      color: '#4caf50',
      detail: info.outputNames.length + ' tensors'
    });

    return stages;
  }

  private getPlanStageIcon(opTypes: string[]): string {
    if (opTypes.some(o => o.toLowerCase().includes('attention'))) return 'visibility';
    if (opTypes.some(o => o.toLowerCase().includes('norm'))) return 'tune';
    if (opTypes.some(o => o.toLowerCase().includes('matmul') || o.toLowerCase().includes('mmul'))) return 'grid_on';
    if (opTypes.some(o => o.toLowerCase().includes('embed'))) return 'text_fields';
    return 'layers';
  }

  getPlanStageColor(stage: CompactPlanStage): string {
    return stage.color;
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

export interface CompactPlanStage {
  label: string;
  icon: string;
  color: string;
  detail: string;
}
