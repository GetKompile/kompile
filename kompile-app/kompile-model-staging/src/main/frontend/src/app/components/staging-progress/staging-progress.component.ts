/*
 *   Copyright 2025 Kompile Inc.
 */

import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subscription } from 'rxjs';
import { StagingService } from '../../services/staging.service';
import { StagingModelInfo, getStatusColor, getStatusIcon } from '../../models/api-models';

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
    this.pollSubscription = this.stagingService.pollStagingStatus(2000).subscribe({
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

  canPromote(model: StagingModelInfo): boolean {
    return model.status === 'ready' || model.status === 'completed';
  }

  canCancel(model: StagingModelInfo): boolean {
    return ['pending', 'downloading', 'converting', 'validating'].includes(model.status);
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }
}
