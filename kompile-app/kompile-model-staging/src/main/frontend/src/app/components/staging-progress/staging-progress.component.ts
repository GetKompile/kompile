/*
 *   Copyright 2025 Kompile Inc.
 */

import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subscription } from 'rxjs';
import { StagingService } from '../../services/staging.service';
import {
  StagingModelInfo,
  StagingStatus,
  getStatusColor,
  getStatusIcon,
  formatBytes
} from '../../models/api-models';

const STEP_ORDER: StagingStatus[] = ['downloading', 'converting', 'validating', 'completed'];

@Component({
  selector: 'app-staging-progress',
  standalone: false,
  templateUrl: './staging-progress.component.html',
  styleUrls: ['./staging-progress.component.css']
})
export class StagingProgressComponent implements OnInit, OnDestroy {

  modelsInStaging: StagingModelInfo[] = [];
  isLoading = true;
  private pollSubscription: Subscription | null = null;

  constructor(
    private stagingService: StagingService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadModels();
    this.startPolling();
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  loadModels(): void {
    this.stagingService.getModelsInStaging().subscribe({
      next: (models) => {
        this.modelsInStaging = models;
        this.isLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.isLoading = false;
        this.showSnackbar('Failed to load staging models: ' + err.message, true);
        this.cdr.detectChanges();
      }
    });
  }

  startPolling(): void {
    this.pollSubscription = this.stagingService.pollStagingStatus(1000).subscribe({
      next: (status) => {
        this.modelsInStaging = status.modelsInStaging || [];
        this.cdr.detectChanges();
      }
    });
  }

  stopPolling(): void {
    if (this.pollSubscription) {
      this.pollSubscription.unsubscribe();
      this.pollSubscription = null;
    }
  }

  promoteModel(modelId: string): void {
    this.stagingService.promoteModelById(modelId).subscribe({
      next: () => {
        this.showSnackbar(`Model ${modelId} promoted to registry`);
        this.loadModels();
      },
      error: (err) => {
        this.showSnackbar('Failed to promote model: ' + err.message, true);
      }
    });
  }

  cancelStaging(modelId: string): void {
    this.stagingService.cancelStaging(modelId).subscribe({
      next: () => {
        this.showSnackbar(`Staging cancelled for ${modelId}`);
        this.loadModels();
      },
      error: (err) => {
        this.showSnackbar('Failed to cancel staging: ' + err.message, true);
      }
    });
  }

  getStatusColor = getStatusColor;
  getStatusIcon = getStatusIcon;
  formatBytes = formatBytes;

  canPromote(model: StagingModelInfo): boolean {
    return model.status === 'ready' || model.status === 'completed';
  }

  canCancel(model: StagingModelInfo): boolean {
    return ['pending', 'downloading', 'converting', 'validating'].includes(model.status);
  }

  isActiveStatus(status: StagingStatus): boolean {
    return ['pending', 'downloading', 'converting', 'validating', 'optimizing', 'promoting'].includes(status);
  }

  getPhaseLabel(status: StagingStatus): string {
    switch (status) {
      case 'pending': return 'Preparing...';
      case 'downloading': return 'Downloading';
      case 'converting': return 'Converting';
      case 'validating': return 'Validating';
      case 'optimizing': return 'Optimizing';
      case 'promoting': return 'Promoting';
      default: return status;
    }
  }

  getStepClass(model: StagingModelInfo, step: StagingStatus): string {
    const modelIdx = STEP_ORDER.indexOf(model.status as StagingStatus);
    const stepIdx = STEP_ORDER.indexOf(step);

    // For ready/completed models, all steps are done
    if (model.status === 'ready' || model.status === 'completed') {
      return 'step-done';
    }

    if (stepIdx < 0 || modelIdx < 0) return '';
    if (stepIdx < modelIdx) return 'step-done';
    if (stepIdx === modelIdx) return 'step-active';
    return '';
  }

  isStepDone(model: StagingModelInfo, step: StagingStatus): boolean {
    if (model.status === 'ready' || model.status === 'completed') return true;
    const modelIdx = STEP_ORDER.indexOf(model.status as StagingStatus);
    const stepIdx = STEP_ORDER.indexOf(step);
    return stepIdx >= 0 && modelIdx >= 0 && stepIdx < modelIdx;
  }

  getEta(model: StagingModelInfo): string {
    if (!model.bytes_per_second || !model.total_bytes || !model.bytes_downloaded) return '...';
    const remaining = model.total_bytes - model.bytes_downloaded;
    if (remaining <= 0) return '0s';
    const seconds = Math.ceil(remaining / model.bytes_per_second);
    if (seconds < 60) return `${seconds}s`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
    return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
  }

  formatTime(isoString: string): string {
    try {
      const date = new Date(isoString);
      return date.toLocaleTimeString();
    } catch {
      return isoString;
    }
  }

  getElapsed(startedAt: string): string {
    try {
      const start = new Date(startedAt).getTime();
      const now = Date.now();
      const seconds = Math.floor((now - start) / 1000);
      if (seconds < 60) return `${seconds}s`;
      if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
      return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
    } catch {
      return '...';
    }
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }
}
