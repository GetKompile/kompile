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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { StagingService, RestoreResult } from '../../services/staging.service';
import {
  ModelEntry,
  OptimizationType,
  OptimizationTypeId,
  QuantizationType,
  QuantizationTypeId,
  OptimizationPreset,
  OptimizationCategory,
  ConfigurableOptimizeRequest,
  ConfigurableOptimizeResult,
  OptimizationDetails,
  ComparisonResult,
  getOptimizationCategoryName,
  getOptimizationCategoryIcon
} from '../../models/api-models';
import { Subscription } from 'rxjs';

interface OptimizationGroup {
  category: OptimizationCategory;
  categoryName: string;
  categoryIcon: string;
  optimizations: OptimizationType[];
}

@Component({
  selector: 'app-optimization-wizard',
  standalone: false,
  templateUrl: './optimization-wizard.component.html',
  styleUrls: ['./optimization-wizard.component.css']
})
export class OptimizationWizardComponent implements OnInit, OnDestroy {

  // Wizard state
  currentStep = 1;
  totalSteps = 4;

  // Data
  models: ModelEntry[] = [];
  optimizations: OptimizationType[] = [];
  quantizationTypes: QuantizationType[] = [];
  presets: OptimizationPreset[] = [];
  optimizationGroups: OptimizationGroup[] = [];

  // Loading states
  loadingModels = false;
  loadingOptimizations = false;
  isOptimizing = false;

  // Selection state
  selectedModel: ModelEntry | null = null;
  selectedPreset: OptimizationPreset | null = null;
  enabledOptimizations: Set<OptimizationTypeId> = new Set();
  selectedQuantization: QuantizationTypeId | null = null;
  createBackup = true;
  forceOptimize = false;

  // Results
  optimizationResult: ConfigurableOptimizeResult | null = null;
  optimizationDetails: OptimizationDetails | null = null;

  // Restore state
  isRestoring = false;
  restoreResult: RestoreResult | null = null;
  showRestoreConfirm = false;

  // Comparison state
  isComparing = false;
  comparisonResult: ComparisonResult | null = null;
  comparisonSampleText = '';

  // History
  optimizationHistory: {
    modelId: string;
    timestamp: Date;
    success: boolean;
    appliedOptimizations: string[];
    timeMs: number;
  }[] = [];

  private subscriptions: Subscription[] = [];

  constructor(
    private stagingService: StagingService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadModels();
    this.loadOptimizations();
    this.loadHistory();
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach(s => s.unsubscribe());
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

  loadOptimizations(): void {
    this.loadingOptimizations = true;
    const sub = this.stagingService.getAvailableOptimizations().subscribe({
      next: (response) => {
        this.optimizations = response.optimizations;
        this.quantizationTypes = response.quantizationTypes;
        this.presets = response.presets;
        this.groupOptimizations();
        this.loadingOptimizations = false;

        // Set default optimizations
        this.setDefaultOptimizations();
      },
      error: (err) => {
        console.error('Failed to load optimizations:', err);
        this.loadingOptimizations = false;
        this.snackBar.open('Failed to load optimization options', 'Close', { duration: 3000 });
      }
    });
    this.subscriptions.push(sub);
  }

  groupOptimizations(): void {
    const groups = new Map<OptimizationCategory, OptimizationType[]>();

    for (const opt of this.optimizations) {
      const category = opt.category;
      if (!groups.has(category)) {
        groups.set(category, []);
      }
      groups.get(category)!.push(opt);
    }

    this.optimizationGroups = Array.from(groups.entries()).map(([category, opts]) => ({
      category,
      categoryName: getOptimizationCategoryName(category),
      categoryIcon: getOptimizationCategoryIcon(category),
      optimizations: opts
    }));
  }

  setDefaultOptimizations(): void {
    this.enabledOptimizations.clear();
    for (const opt of this.optimizations) {
      if (opt.isDefault) {
        this.enabledOptimizations.add(opt.id);
      }
    }
  }

  loadHistory(): void {
    const saved = localStorage.getItem('optimizationHistory');
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        this.optimizationHistory = parsed.map((h: any) => ({
          ...h,
          timestamp: new Date(h.timestamp)
        }));
      } catch {
        this.optimizationHistory = [];
      }
    }
  }

  saveHistory(): void {
    const toSave = this.optimizationHistory.slice(0, 20);
    localStorage.setItem('optimizationHistory', JSON.stringify(toSave));
  }

  // ==================== Step Navigation ====================

  nextStep(): void {
    if (this.canProceed()) {
      this.currentStep++;
    }
  }

  prevStep(): void {
    if (this.currentStep > 1) {
      this.currentStep--;
    }
  }

  goToStep(step: number): void {
    if (step >= 1 && step <= this.totalSteps) {
      this.currentStep = step;
    }
  }

  canProceed(): boolean {
    switch (this.currentStep) {
      case 1:
        return this.selectedModel !== null;
      case 2:
        return this.enabledOptimizations.size > 0;
      case 3:
        return true; // Review step
      case 4:
        return this.optimizationResult !== null;
      default:
        return false;
    }
  }

  // ==================== Model Selection ====================

  selectModel(model: ModelEntry): void {
    this.selectedModel = model;
    this.loadModelOptimizationDetails(model.model_id);
  }

  loadModelOptimizationDetails(modelId: string): void {
    const sub = this.stagingService.getOptimizationDetails(modelId).subscribe({
      next: (details) => {
        this.optimizationDetails = details;
      },
      error: (err) => {
        console.error('Failed to load optimization details:', err);
      }
    });
    this.subscriptions.push(sub);
  }

  isModelOptimized(model: ModelEntry): boolean {
    return model.metadata?.optimized === true;
  }

  getModelTypeDisplay(model: ModelEntry): string {
    switch (model.type) {
      case 'dense_encoder': return 'Dense Encoder';
      case 'sparse_encoder': return 'Sparse Encoder';
      case 'cross_encoder': return 'Cross-Encoder';
      default: return model.type;
    }
  }

  // ==================== Optimization Selection ====================

  selectPreset(preset: OptimizationPreset): void {
    this.selectedPreset = preset;
    this.enabledOptimizations.clear();
    for (const optId of preset.optimizations) {
      this.enabledOptimizations.add(optId);
    }
    if (preset.quantizationType) {
      this.selectedQuantization = preset.quantizationType;
    }
  }

  clearPreset(): void {
    this.selectedPreset = null;
    this.setDefaultOptimizations();
    this.selectedQuantization = null;
  }

  toggleOptimization(optId: OptimizationTypeId): void {
    if (this.enabledOptimizations.has(optId)) {
      this.enabledOptimizations.delete(optId);
    } else {
      this.enabledOptimizations.add(optId);
    }
    // Clear preset selection when manually toggling
    this.selectedPreset = null;
  }

  isOptimizationEnabled(optId: OptimizationTypeId): boolean {
    return this.enabledOptimizations.has(optId);
  }

  selectAllOptimizations(): void {
    for (const opt of this.optimizations) {
      this.enabledOptimizations.add(opt.id);
    }
    this.selectedPreset = null;
  }

  clearAllOptimizations(): void {
    this.enabledOptimizations.clear();
    this.selectedPreset = null;
  }

  toggleQuantization(quantId: QuantizationTypeId): void {
    if (this.selectedQuantization === quantId) {
      this.selectedQuantization = null;
      this.enabledOptimizations.delete('QUANTIZATION');
    } else {
      this.selectedQuantization = quantId;
      this.enabledOptimizations.add('QUANTIZATION');
    }
  }

  // ==================== Optimization Execution ====================

  startOptimization(): void {
    if (!this.selectedModel || this.enabledOptimizations.size === 0) {
      return;
    }

    this.isOptimizing = true;
    this.optimizationResult = null;

    const request: ConfigurableOptimizeRequest = {
      enabledOptimizations: Array.from(this.enabledOptimizations),
      quantizationType: this.selectedQuantization || undefined,
      createBackup: this.createBackup,
      force: this.forceOptimize
    };

    const sub = this.stagingService.optimizeModelConfigurable(
      this.selectedModel.model_id,
      request
    ).subscribe({
      next: (result) => {
        this.optimizationResult = result;
        this.isOptimizing = false;

        if (result.success) {
          this.snackBar.open('Model optimized successfully!', 'Close', { duration: 3000 });

          // Add to history
          this.optimizationHistory.unshift({
            modelId: this.selectedModel!.model_id,
            timestamp: new Date(),
            success: true,
            appliedOptimizations: result.appliedOptimizations || [],
            timeMs: result.optimizationTimeMs || 0
          });
          this.saveHistory();

          // Move to results step
          this.currentStep = 4;
        } else {
          this.snackBar.open(`Optimization failed: ${result.error}`, 'Close', { duration: 5000 });
        }
      },
      error: (err) => {
        this.isOptimizing = false;
        this.optimizationResult = {
          success: false,
          modelId: this.selectedModel!.model_id,
          error: err.message
        };
        this.snackBar.open(`Optimization failed: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
  }

  // ==================== Restore Operations ====================

  showRestoreDialog(): void {
    this.showRestoreConfirm = true;
  }

  cancelRestore(): void {
    this.showRestoreConfirm = false;
  }

  confirmRestore(): void {
    if (!this.selectedModel) {
      return;
    }

    this.showRestoreConfirm = false;
    this.isRestoring = true;
    this.restoreResult = null;

    const sub = this.stagingService.restoreUnoptimized(this.selectedModel.model_id).subscribe({
      next: (result) => {
        this.restoreResult = result;
        this.isRestoring = false;

        if (result.success) {
          this.snackBar.open('Model restored to unoptimized state!', 'Close', { duration: 3000 });

          // Refresh model details
          this.loadModelOptimizationDetails(this.selectedModel!.model_id);

          // Update local model state
          if (this.selectedModel?.metadata) {
            this.selectedModel.metadata.optimized = false;
          }

          // Reload models list to reflect the change
          this.loadModels();
        } else {
          this.snackBar.open(`Restore failed: ${result.error}`, 'Close', { duration: 5000 });
        }
      },
      error: (err) => {
        this.isRestoring = false;
        this.restoreResult = {
          success: false,
          modelId: this.selectedModel!.model_id,
          error: err.message
        };
        this.snackBar.open(`Restore failed: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
  }

  canRestore(): boolean {
    return this.selectedModel !== null &&
           this.isModelOptimized(this.selectedModel) &&
           (this.optimizationDetails?.hasBackup === true);
  }

  // ==================== A/B Comparison ====================

  canCompare(): boolean {
    if (!this.selectedModel) return false;
    // Can compare if model is optimized and has a backup
    if (this.optimizationResult?.success && this.optimizationResult?.backupFile) {
      return true;
    }
    return this.isModelOptimized(this.selectedModel) &&
           (this.optimizationDetails?.hasBackup === true);
  }

  startComparison(): void {
    if (!this.selectedModel || !this.canCompare()) return;

    this.isComparing = true;
    this.comparisonResult = null;

    const request = this.comparisonSampleText
      ? { sampleText: this.comparisonSampleText }
      : {};

    const sub = this.stagingService.compareModels(this.selectedModel.model_id, request).subscribe({
      next: (result) => {
        this.comparisonResult = result;
        this.isComparing = false;

        if (result.success) {
          const verdict = result.outputsMatch ? 'Outputs match!' : 'Outputs differ';
          this.snackBar.open(`Comparison complete: ${verdict}`, 'Close', { duration: 3000 });
        } else {
          this.snackBar.open(`Comparison failed: ${result.error}`, 'Close', { duration: 5000 });
        }
      },
      error: (err) => {
        this.isComparing = false;
        this.comparisonResult = {
          success: false,
          modelId: this.selectedModel!.model_id,
          error: err.message
        };
        this.snackBar.open(`Comparison failed: ${err.message}`, 'Close', { duration: 5000 });
      }
    });
    this.subscriptions.push(sub);
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

  formatDate(date: string | Date | undefined): string {
    if (!date) return 'N/A';
    const d = typeof date === 'string' ? new Date(date) : date;
    return d.toLocaleString();
  }

  getEnabledOptimizationNames(): string[] {
    return Array.from(this.enabledOptimizations)
      .map(id => this.optimizations.find(o => o.id === id)?.displayName || id);
  }

  resetWizard(): void {
    this.currentStep = 1;
    this.selectedModel = null;
    this.selectedPreset = null;
    this.optimizationResult = null;
    this.optimizationDetails = null;
    this.setDefaultOptimizations();
    this.selectedQuantization = null;
    this.createBackup = true;
    this.forceOptimize = false;
    // Reset restore state
    this.isRestoring = false;
    this.restoreResult = null;
    this.showRestoreConfirm = false;
    // Reset comparison state
    this.isComparing = false;
    this.comparisonResult = null;
    this.comparisonSampleText = '';
  }

  clearHistory(): void {
    this.optimizationHistory = [];
    localStorage.removeItem('optimizationHistory');
    this.snackBar.open('History cleared', 'Close', { duration: 2000 });
  }
}
