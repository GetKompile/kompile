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
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subscription, interval } from 'rxjs';
import { StagingService } from '../../services/staging.service';
import { ModelRegistry, ModelEntry, ModelType, getStatusColor, getStatusIcon, getModelTypeIcon, getModelTypeDisplayName } from '../../models/api-models';
import { ModelDetailsDialogComponent, ModelDetailsDialogData } from '../model-details-dialog/model-details-dialog.component';

@Component({
  selector: 'app-registry-browser',
  standalone: false,
  templateUrl: './registry-browser.component.html',
  styleUrls: ['./registry-browser.component.css']
})
export class RegistryBrowserComponent implements OnInit, OnDestroy {

  registry: ModelRegistry | null = null;
  isLoading = true;
  error: string | null = null;
  selectedType: string = 'all';
  searchQuery: string = '';
  private pollSubscription?: Subscription;

  // Stats
  stats = {
    total: 0,
    denseEncoders: 0,
    sparseEncoders: 0,
    crossEncoders: 0,
    active: 0
  };

  modelTypes = [
    { value: 'all', label: 'All Models', icon: 'apps' },
    { value: 'retrieval', label: 'Retrieval (All Encoders)', icon: 'search' },
    { value: 'dense_encoder', label: 'Dense Encoders', icon: 'hub' },
    { value: 'sparse_encoder', label: 'Sparse Encoders', icon: 'scatter_plot' },
    { value: 'cross_encoder', label: 'Cross-Encoders (Rerankers)', icon: 'compare_arrows' }
  ];

  constructor(
    private stagingService: StagingService,
    private dialog: MatDialog,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadRegistry();
    this.startPolling();
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  startPolling(): void {
    // Poll every 5 seconds for registry updates
    this.pollSubscription = interval(5000).subscribe(() => {
      this.refreshRegistry();
    });
  }

  stopPolling(): void {
    this.pollSubscription?.unsubscribe();
  }

  refreshRegistry(): void {
    // Silent refresh without loading indicator
    this.stagingService.getRegistry().subscribe({
      next: (registry) => {
        this.registry = registry;
        this.calculateStats();
        this.cdr.detectChanges();
      }
    });
  }

  loadRegistry(): void {
    this.isLoading = true;
    this.error = null;

    this.stagingService.getRegistry().subscribe({
      next: (registry) => {
        this.registry = registry;
        this.calculateStats();
        this.isLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.error = err.message;
        this.isLoading = false;
        this.cdr.detectChanges();
      }
    });
  }

  calculateStats(): void {
    if (!this.registry?.models) {
      this.stats = { total: 0, denseEncoders: 0, sparseEncoders: 0, crossEncoders: 0, active: 0 };
      return;
    }

    const models = Object.values(this.registry.models);
    this.stats = {
      total: models.length,
      denseEncoders: models.filter(m => m.type === 'dense_encoder' || m.type === 'encoder').length,
      sparseEncoders: models.filter(m => m.type === 'sparse_encoder').length,
      crossEncoders: models.filter(m => m.type === 'cross_encoder').length,
      active: models.filter(m => m.status === 'active').length
    };
  }

  getModels(): ModelEntry[] {
    if (!this.registry?.models) return [];

    let models = Object.values(this.registry.models);

    // Filter by type
    if (this.selectedType !== 'all') {
      if (this.selectedType === 'retrieval') {
        // Show all retrieval models (dense + sparse + legacy encoder)
        models = models.filter(m =>
          m.type === 'dense_encoder' || m.type === 'sparse_encoder' || m.type === 'encoder'
        );
      } else {
        models = models.filter(m => m.type === this.selectedType);
      }
    }

    // Filter by search query
    if (this.searchQuery.trim()) {
      const query = this.searchQuery.toLowerCase();
      models = models.filter(m =>
        m.model_id.toLowerCase().includes(query) ||
        m.metadata?.description?.toLowerCase().includes(query) ||
        m.metadata?.sourceRepository?.toLowerCase().includes(query)
      );
    }

    // Sort by model ID
    return models.sort((a, b) => a.model_id.localeCompare(b.model_id));
  }

  openModelDetails(model: ModelEntry): void {
    const dialogData: ModelDetailsDialogData = {
      model: model,
      editable: true
    };

    this.dialog.open(ModelDetailsDialogComponent, {
      width: '700px',
      maxHeight: '90vh',
      data: dialogData
    });
  }

  // Helper functions
  getStatusColor = getStatusColor;
  getStatusIcon = getStatusIcon;
  getModelTypeIcon = getModelTypeIcon;
  getModelTypeDisplayName = getModelTypeDisplayName;

  formatMetadata(model: ModelEntry): string {
    const parts: string[] = [];
    if (model.metadata?.embeddingDim) parts.push(`${model.metadata.embeddingDim}d`);
    if (model.metadata?.maxSequenceLength) parts.push(`max ${model.metadata.maxSequenceLength} tokens`);
    if (model.metadata?.framework) parts.push(model.metadata.framework);
    return parts.join(' | ');
  }

  getTokenizerSummary(model: ModelEntry): string {
    if (!model.tokenizer) return 'Default config';
    const parts: string[] = [];
    if (model.tokenizer.do_lower_case) parts.push('lowercase');
    if (model.tokenizer.add_special_tokens) parts.push('special tokens');
    if (model.tokenizer.max_length) {
      parts.push(`max ${model.tokenizer.max_length}`);
    }
    return parts.length > 0 ? parts.join(', ') : 'Default config';
  }

  getVocabInfo(model: ModelEntry): string {
    if (model.vocab_file) {
      return model.vocab_file;
    }
    return 'vocab.txt';
  }

  copyModelId(model: ModelEntry, event: Event): void {
    event.stopPropagation();
    navigator.clipboard.writeText(model.model_id).then(() => {
      this.snackBar.open('Model ID copied to clipboard', 'Close', {
        duration: 2000
      });
    });
  }
}
