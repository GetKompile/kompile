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
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subscription, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { StagingService } from '../../services/staging.service';
import {
  ModelRegistry,
  ModelEntry,
  ModelType,
  StagingStatusResponse,
  StagingModelInfo,
  getStatusColor,
  getStatusIcon,
  getModelTypeIcon,
  getModelTypeDisplayName,
  getModelTypeDescription,
  getModelTypeRole
} from '../../models/api-models';

@Component({
  selector: 'app-dashboard',
  standalone: false,
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit, OnDestroy {

  registry: ModelRegistry | null = null;
  stagingStatus: StagingStatusResponse | null = null;
  modelsInStaging: StagingModelInfo[] = [];
  activeModelsByRole: { role: string; type: ModelType; model: ModelEntry | null; icon: string; description: string }[] = [];

  isLoading = true;
  error: string | null = null;

  private pollSubscription: Subscription | null = null;
  private registryPollSubscription: Subscription | null = null;
  autoRefresh = true;

  constructor(
    private stagingService: StagingService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadData();
    this.startPolling();
  }

  ngOnDestroy(): void {
    this.stopPolling();
    this.stopRegistryPolling();
  }

  loadData(): void {
    this.isLoading = true;
    this.error = null;

    // Load registry and staging status in parallel
    Promise.all([
      this.loadRegistry(),
      this.loadStagingStatus()
    ]).finally(() => {
      this.isLoading = false;
      this.cdr.detectChanges();
    });
  }

  private loadRegistry(): Promise<void> {
    return new Promise((resolve) => {
      this.stagingService.getRegistry().subscribe({
        next: (registry) => {
          this.registry = registry;
          this.computeActiveModelAssignments();
          resolve();
        },
        error: (err) => {
          console.error('Failed to load registry:', err);
          resolve();
        }
      });
    });
  }

  private loadStagingStatus(): Promise<void> {
    return new Promise((resolve) => {
      this.stagingService.getStagingStatus().subscribe({
        next: (status) => {
          this.stagingStatus = status;
          this.modelsInStaging = status.modelsInStaging || [];
          resolve();
        },
        error: (err) => {
          console.error('Failed to load staging status:', err);
          resolve();
        }
      });
    });
  }

  refresh(): void {
    this.loadData();
    this.showSnackbar('Data refreshed');
  }

  startPolling(): void {
    if (this.autoRefresh && !this.pollSubscription) {
      this.pollSubscription = this.stagingService.pollStagingStatus(5000).subscribe({
        next: (status) => {
          this.stagingStatus = status;
          this.modelsInStaging = status.modelsInStaging || [];
          this.cdr.detectChanges();
        }
      });
    }
    if (this.autoRefresh && !this.registryPollSubscription) {
      this.registryPollSubscription = timer(5000, 5000).pipe(
        switchMap(() => this.stagingService.getRegistry())
      ).subscribe({
        next: (registry) => {
          this.registry = registry;
          this.computeActiveModelAssignments();
          this.cdr.detectChanges();
        }
      });
    }
  }

  stopPolling(): void {
    if (this.pollSubscription) {
      this.pollSubscription.unsubscribe();
      this.pollSubscription = null;
    }
  }

  stopRegistryPolling(): void {
    if (this.registryPollSubscription) {
      this.registryPollSubscription.unsubscribe();
      this.registryPollSubscription = null;
    }
  }

  toggleAutoRefresh(): void {
    this.autoRefresh = !this.autoRefresh;
    if (this.autoRefresh) {
      this.startPolling();
      this.showSnackbar('Auto-refresh enabled');
    } else {
      this.stopPolling();
      this.stopRegistryPolling();
      this.showSnackbar('Auto-refresh disabled');
    }
  }

  // ==================== Active Model Assignments ====================

  private computeActiveModelAssignments(): void {
    const roleTypes: ModelType[] = [
      'dense_encoder', 'sparse_encoder', 'cross_encoder',
      'ocr_detection', 'ocr_recognition', 'ocr_table',
      'layout_model', 'ocr_pipeline', 'document_classifier', 'vlm_pipeline'
    ];

    const models = this.registry?.models ? Object.values(this.registry.models) : [];

    this.activeModelsByRole = roleTypes.map(type => {
      const activeModel = models.find(m =>
        (m.type === type || (type === 'dense_encoder' && m.type === 'encoder')) && m.status === 'active'
      ) || null;

      return {
        role: getModelTypeRole(type),
        type,
        model: activeModel,
        icon: getModelTypeIcon(type),
        description: getModelTypeDescription(type)
      };
    });
  }

  hasAnyActiveModels(): boolean {
    return this.activeModelsByRole.some(r => r.model !== null);
  }

  hasAnyUnassignedRoles(): boolean {
    // Only check roles that have at least one model of that type (active or not)
    if (!this.registry?.models) return false;
    const models = Object.values(this.registry.models);
    return this.activeModelsByRole.some(r => {
      const hasModelsOfType = models.some(m => m.type === r.type || (r.type === 'dense_encoder' && m.type === 'encoder'));
      return hasModelsOfType && r.model === null;
    });
  }

  getModelTypeDisplayName = getModelTypeDisplayName;

  // ==================== Stats Helpers ====================

  getRegistryModelCount(): number {
    return this.registry?.models ? Object.keys(this.registry.models).length : 0;
  }

  getEncoderCount(): number {
    if (!this.registry?.models) return 0;
    return Object.values(this.registry.models).filter(m =>
      m.type === 'encoder' || m.type === 'dense_encoder' || m.type === 'sparse_encoder'
    ).length;
  }

  getCrossEncoderCount(): number {
    if (!this.registry?.models) return 0;
    return Object.values(this.registry.models).filter(m => m.type === 'cross_encoder').length;
  }

  getVlmPipelineCount(): number {
    if (!this.registry?.models) return 0;
    return Object.values(this.registry.models).filter(m => m.type === 'vlm_pipeline').length;
  }

  getOcrModelCount(): number {
    if (!this.registry?.models) return 0;
    const ocrTypes = ['ocr_detection', 'ocr_recognition', 'ocr_table', 'ocr_pipeline', 'layout_model'];
    return Object.values(this.registry.models).filter(m => ocrTypes.includes(m.type)).length;
  }

  getLlmModelCount(): number {
    if (!this.registry?.models) return 0;
    return Object.values(this.registry.models).filter(m => m.type === 'llm_ggml').length;
  }

  getActiveCount(): number {
    if (!this.registry?.models) return 0;
    return Object.values(this.registry.models).filter(m => m.status === 'active').length;
  }

  getStagingCount(): number {
    return this.modelsInStaging.length;
  }

  getProcessingCount(): number {
    return this.modelsInStaging.filter(m =>
      ['downloading', 'converting', 'validating', 'promoting'].includes(m.status)
    ).length;
  }

  getReadyCount(): number {
    return this.modelsInStaging.filter(m => m.status === 'ready').length;
  }

  getFailedCount(): number {
    return this.modelsInStaging.filter(m => m.status === 'failed').length;
  }

  // ==================== Helper Methods ====================

  getStatusColor = getStatusColor;
  getStatusIcon = getStatusIcon;
  getModelTypeIcon = getModelTypeIcon;

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 3000,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }
}
