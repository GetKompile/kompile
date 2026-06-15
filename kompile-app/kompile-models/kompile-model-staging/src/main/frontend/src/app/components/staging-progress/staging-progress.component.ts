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
  private sseConnections: Map<string, EventSource> = new Map();

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
    this.closeAllSseConnections();
  }

  loadModels(): void {
    this.stagingService.getModelsInStaging().subscribe({
      next: (models) => {
        this.modelsInStaging = models;
        this.isLoading = false;
        this.connectSseForActiveModels(models);
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
    // Poll at a slower interval (5s) since SSE handles real-time updates.
    // Polling discovers new models and handles SSE reconnection.
    this.pollSubscription = this.stagingService.pollStagingStatus(5000).subscribe({
      next: (status) => {
        const models = status.modelsInStaging || [];
        this.modelsInStaging = models;
        this.connectSseForActiveModels(models);
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

  /**
   * Open SSE connections for active (non-terminal) staging models.
   * Closes connections for models that are no longer active.
   */
  private connectSseForActiveModels(models: StagingModelInfo[]): void {
    const activeIds = new Set<string>();

    for (const model of models) {
      if (this.isActiveStatus(model.status)) {
        activeIds.add(model.model_id);
        if (!this.sseConnections.has(model.model_id)) {
          this.openSseConnection(model.model_id);
        }
      }
    }

    // Close SSE for models that are no longer active
    for (const [modelId, es] of this.sseConnections) {
      if (!activeIds.has(modelId)) {
        es.close();
        this.sseConnections.delete(modelId);
      }
    }
  }

  /**
   * Open an SSE connection for a specific model and handle status events.
   */
  private openSseConnection(modelId: string): void {
    const es = this.stagingService.connectToStagingStream(modelId);

    es.addEventListener('status', (event: any) => {
      try {
        const updated: StagingModelInfo = JSON.parse(event.data);
        const idx = this.modelsInStaging.findIndex(m => m.model_id === modelId);
        if (idx >= 0) {
          this.modelsInStaging[idx] = updated;
        } else {
          this.modelsInStaging.push(updated);
        }
        this.cdr.detectChanges();

        // Close connection on terminal states
        if (updated.status === 'completed' || updated.status === 'failed' || updated.status === 'ready') {
          es.close();
          this.sseConnections.delete(modelId);
        }
      } catch (e) {
        // Ignore parse errors
      }
    });

    es.onerror = () => {
      // EventSource will auto-reconnect on error.
      // If the server closed the stream (terminal state), close our side too.
      if (es.readyState === EventSource.CLOSED) {
        this.sseConnections.delete(modelId);
      }
    };

    this.sseConnections.set(modelId, es);
  }

  private closeAllSseConnections(): void {
    for (const [, es] of this.sseConnections) {
      es.close();
    }
    this.sseConnections.clear();
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }
}
