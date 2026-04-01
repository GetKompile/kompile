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

import { Component, Inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CatalogModel, formatBytes, resolvePassDetail, getCategoryColor, OptimizationPassDetail } from '../../models/api-models';

@Component({
  selector: 'app-catalog-model-info-dialog',
  standalone: false,
  templateUrl: './catalog-model-info-dialog.component.html',
  styleUrls: ['./catalog-model-info-dialog.component.css']
})
export class CatalogModelInfoDialogComponent {

  model: CatalogModel;

  constructor(
    public dialogRef: MatDialogRef<CatalogModelInfoDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: CatalogModel,
    private snackBar: MatSnackBar
  ) {
    this.model = data;
  }

  getSourceIcon(source: string): string {
    switch (source?.toLowerCase()) {
      case 'huggingface': return 'hub';
      case 'github': return 'code';
      default: return 'cloud';
    }
  }

  getModelTypeLabel(type?: string): string {
    if (!type) return 'Unknown';
    switch (type) {
      case 'dense_encoder': return 'Dense Encoder';
      case 'sparse_encoder': return 'Sparse Encoder';
      case 'cross_encoder': return 'Cross-Encoder (Reranker)';
      case 'vlm_pipeline': return 'VLM Pipeline';
      case 'vlm_vision_encoder': return 'VLM Vision Encoder';
      case 'vlm_decoder': return 'VLM Decoder';
      case 'vlm_embed_tokens': return 'VLM Embed Tokens';
      case 'ocr_detection': return 'OCR Detection';
      case 'ocr_recognition': return 'OCR Recognition';
      case 'llm_ggml': return 'LLM (GGML)';
      default: return type;
    }
  }

  getOverviewItems(): { label: string; value: any; icon: string }[] {
    const items: { label: string; value: any; icon: string }[] = [];
    const m = this.model;
    const meta = m.metadata;

    items.push({ label: 'Model ID', value: m.id, icon: 'badge' });
    items.push({ label: 'Source', value: m.source, icon: this.getSourceIcon(m.source) });
    items.push({ label: 'Repository', value: m.repo, icon: 'folder' });
    items.push({ label: 'Format', value: m.format, icon: 'transform' });

    if (m.modelType) {
      items.push({ label: 'Model Type', value: this.getModelTypeLabel(m.modelType), icon: 'category' });
    }
    if (m.status) {
      items.push({ label: 'Status', value: m.status, icon: 'info' });
    }
    if (m.installed != null) {
      items.push({ label: 'Installed', value: m.installed ? 'Yes' : 'No', icon: m.installed ? 'check_circle' : 'cancel' });
    }
    if (m.path) {
      items.push({ label: 'Path', value: m.path, icon: 'folder_open' });
    }

    return items;
  }

  getMetadataItems(): { label: string; value: any; icon: string }[] {
    const items: { label: string; value: any; icon: string }[] = [];
    const meta = this.model.metadata;
    if (!meta) return items;

    if (meta.description) {
      items.push({ label: 'Description', value: meta.description, icon: 'description' });
    }
    if (meta.embeddingDim) {
      items.push({ label: 'Embedding Dimensions', value: meta.embeddingDim, icon: 'straighten' });
    }
    if (meta.hiddenSize) {
      items.push({ label: 'Hidden Size', value: meta.hiddenSize, icon: 'layers' });
    }
    if (meta.numLayers) {
      items.push({ label: 'Number of Layers', value: meta.numLayers, icon: 'view_module' });
    }
    if (meta.maxSequenceLength) {
      items.push({ label: 'Max Sequence Length', value: meta.maxSequenceLength, icon: 'text_fields' });
    }
    if (meta.trainingData) {
      items.push({ label: 'Training Data', value: meta.trainingData, icon: 'school' });
    }

    return items;
  }

  getFileItems(): { label: string; value: string; icon: string }[] {
    const items: { label: string; value: string; icon: string }[] = [];
    if (this.model.files) {
      if (this.model.files.model) {
        items.push({ label: 'Model File', value: this.model.files.model, icon: 'insert_drive_file' });
      }
      if (this.model.files.vocab) {
        items.push({ label: 'Vocabulary File', value: this.model.files.vocab, icon: 'description' });
      }
    }
    return items;
  }

  hasOptimizationData(): boolean {
    return !!this.model.metadata?.optimized;
  }

  getOptimizationItems(): { label: string; value: any; icon: string }[] {
    const items: { label: string; value: any; icon: string }[] = [];
    const meta = this.model.metadata;
    if (!meta) return items;

    items.push({ label: 'Optimized', value: meta.optimized ? 'Yes' : 'No', icon: 'speed' });

    if (meta.optimized_at) {
      items.push({ label: 'Optimized At', value: new Date(meta.optimized_at).toLocaleString(), icon: 'event' });
    }
    if (meta.optimization_time_ms != null) {
      const ms = meta.optimization_time_ms;
      const timeStr = ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
      items.push({ label: 'Optimization Time', value: timeStr, icon: 'timer' });
    }

    const stats = meta.optimization_stats;
    if (stats) {
      if (stats.reduction_percent != null) {
        items.push({ label: 'Size Reduction', value: stats.reduction_percent.toFixed(1) + '%', icon: 'compress' });
      }
      if (stats.ops_before != null && stats.ops_after != null) {
        items.push({ label: 'Operations', value: `${stats.ops_before} → ${stats.ops_after}`, icon: 'functions' });
      }
      if (stats.vars_before != null && stats.vars_after != null) {
        items.push({ label: 'Variables', value: `${stats.vars_before} → ${stats.vars_after}`, icon: 'data_array' });
      }
      if (stats.size_before_bytes != null && stats.size_after_bytes != null) {
        items.push({
          label: 'Model Size',
          value: `${formatBytes(stats.size_before_bytes)} → ${formatBytes(stats.size_after_bytes)}`,
          icon: 'storage'
        });
      }
    }

    const config = meta.optimization_config;
    if (config) {
      if (config.preset) {
        items.push({ label: 'Preset', value: config.preset, icon: 'tune' });
      }
      if (config.quantization_type) {
        items.push({ label: 'Quantization', value: config.quantization_type, icon: 'compress' });
      }
      if (config.max_iterations != null) {
        items.push({ label: 'Max Iterations', value: config.max_iterations, icon: 'repeat' });
      }
    }

    return items;
  }

  getAppliedOptimizations(): string[] {
    return this.model.metadata?.applied_optimizations || [];
  }

  getResolvedPasses(): OptimizationPassDetail[] {
    return this.getAppliedOptimizations().map(p => resolvePassDetail(p));
  }

  getResolvedConfigPasses(): OptimizationPassDetail[] {
    const passes = this.model.metadata?.optimization_config?.enabled_passes || [];
    return passes.map(p => resolvePassDetail(p));
  }

  getCategoryColor = getCategoryColor;

  copyModelJson(): void {
    const jsonString = JSON.stringify(this.model, null, 2);
    navigator.clipboard.writeText(jsonString).then(() => {
      this.snackBar.open('JSON copied to clipboard', 'Close', { duration: 2000 });
    });
  }

  close(): void {
    this.dialogRef.close();
  }
}
