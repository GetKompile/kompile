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
import { Subscription } from 'rxjs';
import { StagingService } from '../../services/staging.service';
import {
  ModelRegistry,
  StagingStatusResponse,
  StagingModelInfo,
  getStatusColor,
  getStatusIcon,
  getModelTypeIcon
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

  isLoading = true;
  error: string | null = null;

  private pollSubscription: Subscription | null = null;
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
  }

  stopPolling(): void {
    if (this.pollSubscription) {
      this.pollSubscription.unsubscribe();
      this.pollSubscription = null;
    }
  }

  toggleAutoRefresh(): void {
    this.autoRefresh = !this.autoRefresh;
    if (this.autoRefresh) {
      this.startPolling();
      this.showSnackbar('Auto-refresh enabled');
    } else {
      this.stopPolling();
      this.showSnackbar('Auto-refresh disabled');
    }
  }

  // ==================== Stats Helpers ====================

  getRegistryModelCount(): number {
    return this.registry?.models ? Object.keys(this.registry.models).length : 0;
  }

  getEncoderCount(): number {
    if (!this.registry?.models) return 0;
    return Object.values(this.registry.models).filter(m => m.type === 'encoder').length;
  }

  getCrossEncoderCount(): number {
    if (!this.registry?.models) return 0;
    return Object.values(this.registry.models).filter(m => m.type === 'cross_encoder').length;
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
