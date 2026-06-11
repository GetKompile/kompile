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
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatMenuModule } from '@angular/material/menu';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatBadgeModule } from '@angular/material/badge';
import { MatSelectModule } from '@angular/material/select';
import { Subject, forkJoin, Subscription, interval, of } from 'rxjs';
import { takeUntil, filter, switchMap, catchError } from 'rxjs/operators';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import { SdkHubComponent } from '../sdk-hub/sdk-hub.component';

import {
  StagingConfigService,
  StagingServiceConfig,
  StagingServiceConfigDto,
  ConnectionTestResult,
  ActiveModelsResponse,
  ConnectionStatus,
  RetryConnectionResult,
  RetryDiscoverResult,
  OptimizationStatus,
  RestoreResult,
  LocalModelRegistry,
  LocalModelEntry,
  OptimizeResult
} from '../../services/staging-config.service';
import { ArchiveService } from '../../services/archive.service';
import { ModelRegistryService } from '../../services/model-registry.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { WebSocketService, WebSocketConnectionState } from '../../services/websocket.service';
import { ProcessingSettingsService, GraphOptimizationStatus, GraphOptimizationResult } from '../../services/processing-settings.service';
import {
  ArchiveInfo,
  ArchiveStatus,
  ArchiveModelInfo,
  FactSheet,
  ModelStatusUpdate,
  UpdateFactSheetRequest
} from '../../models/api-models';

// ─── Local interfaces for API response shapes ────────────────────────────────

interface EmbeddingModelStatus {
  available: boolean;
  modelId?: string;
  activeModelId?: string;
  dimensions?: number;
  loading?: boolean;
  initialized?: boolean;
  error?: string;
  source?: string;
}

interface RemoteModelEntry {
  modelId: string;
  type: string;
  status: string;
  metadata?: {
    embeddingDim?: number;
    description?: string;
    [key: string]: unknown;
  };
  version?: string;
  [key: string]: unknown;
}

interface RemoteModelRegistry {
  models: Record<string, RemoteModelEntry>;
  version?: string;
  lastUpdated?: string;
}

// ────────────────────────────────────────────────────────────────────────────

@Component({
  selector: 'app-staging-config',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatCheckboxModule,
    MatSlideToggleModule,
    MatProgressSpinnerModule,
    MatProgressBarModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatChipsModule,
    MatDividerModule,
    MatDialogModule,
    MatExpansionModule,
    MatMenuModule,
    MatTabsModule,
    MatTableModule,
    MatBadgeModule,
    MatSelectModule,
    ConfirmDialogComponent,
    SdkHubComponent
  ],
  templateUrl: './staging-config.component.html',
  styleUrls: ['./staging-config.component.css']
})
export class StagingConfigComponent implements OnInit, OnDestroy {

  // Tab state
  activeTabIndex = 0;

  // === Archive State ===
  archives: ArchiveInfo[] = [];
  archiveStatus: ArchiveStatus | null = null;
  archiveModels: ArchiveModelInfo[] = [];
  archiveLoading = false;
  loadingArchive: string | null = null;
  extractingModel: string | null = null;

  // === Remote Staging State ===
  configs: StagingServiceConfig[] = [];
  activeConfig: StagingServiceConfig | null = null;
  loading = false;
  testingId: number | null = null;

  // === Active Model State ===
  activeModelsByType: { [type: string]: string } = {};
  activatingModel: string | null = null;
  loadingModel: string | null = null;
  currentEmbeddingModel: string | null = null;
  embeddingModelStatus: EmbeddingModelStatus | null = null;
  embeddingModelReady = false;
  embeddingModelLoading = false;
  embeddingOptimalBatchSize: number | null = null;
  embeddingLoadingPhase: string | null = null;
  embeddingLoadingMessage: string | null = null;
  embeddingLoadingElapsedMs = 0;

  // === Download State ===
  downloadingModel: string | null = null;
  downloadProgress = 0;
  downloadStatus = '';
  downloadSubscription: Subscription | null = null;
  downloadedModels: Set<string> = new Set();

  // === Remote Registry State ===
  remoteRegistry: RemoteModelRegistry | null = null;
  remoteRegistryLoading = false;
  promotingModel: string | null = null;

  // === Connected Apps State ===
  loadingConnectedApps = false;

  // === Connection Status & Retry State ===
  connectionStatus: ConnectionStatus | null = null;
  retryingConnection = false;
  showConnectionRetryBanner = false;

  // === LLM Serving Status ===
  llmServingStatus: any = null;
  llmServingPolling = false;

  // Form state
  showForm = false;
  editingConfig: StagingServiceConfig | null = null;
  configForm!: FormGroup;

  // Collapsible panel state
  remoteStagingCollapsed = false;

  // Force reload state
  forceReloading = false;

  // Active fact sheet for persisting model selections
  activeFactSheet: FactSheet | null = null;

  // === Graph Optimization State ===
  graphOptimizationStatus: GraphOptimizationStatus = { available: false };
  isOptimizing = false;
  lastOptimizationResult: GraphOptimizationResult | null = null;

  // === Local Model Registry State ===
  localRegistry: LocalModelRegistry | null = null;
  localRegistryLoading = false;
  optimizingModelId: string | null = null;

  private destroy$ = new Subject<void>();

  constructor(
    private stagingConfigService: StagingConfigService,
    private archiveService: ArchiveService,
    private modelRegistryService: ModelRegistryService,
    private factSheetService: FactSheetService,
    private webSocketService: WebSocketService,
    private processingSettingsService: ProcessingSettingsService,
    private snackBar: MatSnackBar,
    private fb: FormBuilder,
    private cdr: ChangeDetectorRef,
    private dialog: MatDialog
  ) {
    this.initForm();
  }

  ngOnInit(): void {
    // Load all data
    this.loadArchiveData();
    this.loadRemoteStagingData();
    this.loadEmbeddingStatus();
    this.loadGraphOptimizationStatus();
    this.loadLocalRegistry();
    this.pollLlmServingStatus();

    // Connect to WebSocket and subscribe to model status updates
    this.setupWebSocketSubscription();

    // Check downloaded models on startup (after registry loads)
    // This is also called in loadRemoteRegistry, but we call it here for initial load

    // Subscribe to registry changes
    this.modelRegistryService.changes$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(() => {
      this.loadArchiveData();
    });

    // Subscribe to active fact sheet and restore tab based on embeddingModelSource
    this.factSheetService.activeSheet$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(sheet => {
      this.activeFactSheet = sheet;
      // Restore tab selection based on saved model source
      if (sheet?.embeddingModelSource === 'archive') {
        this.activeTabIndex = 0;
      } else if (sheet?.embeddingModelSource === 'staging') {
        this.activeTabIndex = 1;
      }
    });

    // Subscribe to remote staging configs
    this.stagingConfigService.configs$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(configs => {
      this.configs = configs;
    });

    this.stagingConfigService.activeConfig$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(config => {
      this.activeConfig = config;
    });

    this.stagingConfigService.loading$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(loading => {
      this.loading = loading;
    });

    // Subscribe to archive state
    this.archiveService.archives$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(archives => {
      this.archives = archives;
    });

    this.archiveService.status$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(status => {
      this.archiveStatus = status;
    });

    this.archiveService.models$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(models => {
      this.archiveModels = models;
    });
  }

  pollLlmServingStatus(): void {
    // Poll LLM serving status every 10 seconds
    interval(10000).pipe(
      takeUntil(this.destroy$),
      switchMap(() => this.stagingConfigService.getLlmServingStatus().pipe(
        catchError(() => of(null))
      ))
    ).subscribe((status: any) => {
      this.llmServingStatus = status;
      this.cdr.markForCheck();
    });
    // Initial fetch
    this.stagingConfigService.getLlmServingStatus().pipe(
      catchError(() => of(null))
    ).subscribe((status: any) => {
      this.llmServingStatus = status;
      this.cdr.markForCheck();
    });
  }

  ngOnDestroy(): void {
    // Unsubscribe from WebSocket model status updates
    this.webSocketService.unsubscribeFromModelStatus();
    this.destroy$.next();
    this.destroy$.complete();
  }

  getModelTypeLabel(type: string): string {
    switch (type) {
      case 'dense_encoder': return 'Dense Encoder';
      case 'sparse_encoder': return 'Sparse Encoder';
      case 'cross_encoder': return 'Cross-Encoder';
      default: return type;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // MODEL SOURCE SWITCHING
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Switch to a specific model source (archive or staging).
   * This updates the tab, persists the choice, and notifies the backend.
   */
  switchToSource(source: 'archive' | 'staging'): void {
    // Update the tab index
    this.activeTabIndex = source === 'archive' ? 0 : 1;

    // Persist the source choice to the fact sheet
    this.persistSourceToFactSheet(source);
  }

  /**
   * Handle tab change events from the mat-tab-group.
   * Called when user clicks directly on a tab.
   */
  onTabChange(index: number): void {
    const source = index === 0 ? 'archive' : 'staging';
    this.persistSourceToFactSheet(source);
  }

  /**
   * Persist the model source choice to the active fact sheet.
   * This ensures the choice is remembered across restarts.
   */
  private persistSourceToFactSheet(source: 'archive' | 'staging'): void {
    if (!this.activeFactSheet) {
      console.warn('No active fact sheet to persist source to');
      return;
    }

    // Don't update if already set to this source
    if (this.activeFactSheet.embeddingModelSource === source) {
      return;
    }

    const request: UpdateFactSheetRequest = {
      embeddingModelSource: source
    };

    // If switching to staging, clear the archive ID
    if (source === 'staging') {
      request.embeddingArchiveId = undefined;
    }

    this.factSheetService.updateSheet(this.activeFactSheet.id, request).subscribe({
      next: (updatedSheet) => {
        console.log(`Switched model source to '${source}' for fact sheet '${this.activeFactSheet?.name}'`);
        // Update local reference
        if (updatedSheet) {
          this.activeFactSheet = updatedSheet;
        }
        // Notify registry service so the model status indicator refreshes
        if (source === 'staging') {
          this.modelRegistryService.notifyStagingApplied();
        } else {
          this.modelRegistryService.notifyRegistryRefreshed();
        }
        this.showSnackbar(`Model source switched to ${source === 'archive' ? 'Archives' : 'Remote Staging'}`);
      },
      error: (err) => {
        console.error('Failed to persist source to fact sheet:', err);
        this.showSnackbar('Failed to switch model source', true);
      }
    });
  }

  /**
   * Persist the loaded model to the active fact sheet so it will be loaded on restart.
   * @param modelId The model ID to persist
   * @param source The source type: 'archive' for local archives, 'staging' for remote staging service
   */
  private persistModelToFactSheet(modelId: string, source: 'archive' | 'staging'): void {
    if (!this.activeFactSheet) {
      console.warn('No active fact sheet to persist model to');
      return;
    }

    const request: UpdateFactSheetRequest = {
      embeddingModel: modelId,
      embeddingModelSource: source
    };

    // Clear archive ID if switching to staging source
    if (source === 'staging') {
      request.embeddingArchiveId = undefined;
    }

    this.factSheetService.updateSheet(this.activeFactSheet.id, request).subscribe({
      next: () => {
        console.log(`Persisted embedding model ${modelId} (${source}) to fact sheet ${this.activeFactSheet?.name}`);
        // Notify registry service so the model status indicator refreshes
        if (source === 'staging') {
          this.modelRegistryService.notifyStagingApplied();
        } else {
          this.modelRegistryService.notifyRegistryRefreshed();
        }
      },
      error: (err) => {
        console.error('Failed to persist model to fact sheet:', err);
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ARCHIVE METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  loadArchiveData(): void {
    this.archiveLoading = true;
    forkJoin([
      this.archiveService.listArchives(),
      this.archiveService.getStatus()
    ]).subscribe({
      next: () => {
        this.archiveLoading = false;
      },
      error: () => {
        this.archiveLoading = false;
      }
    });
  }

  loadArchive(archive: ArchiveInfo): void {
    this.loadingArchive = archive.path;
    this.archiveService.loadArchive(archive.path).subscribe({
      next: (status) => {
        this.loadingArchive = null;
        if (status.loaded) {
          this.showSnackbar(`Loaded archive: ${archive.name}`);
          // Persist the archive selection to the fact sheet
          this.persistArchiveToFactSheet(archive.archiveId || archive.name);
        } else {
          this.showSnackbar('Failed to load archive', true);
        }
      },
      error: () => {
        this.loadingArchive = null;
        this.showSnackbar('Failed to load archive', true);
      }
    });
  }

  /**
   * Persist the loaded archive to the fact sheet so it will be loaded on restart.
   */
  private persistArchiveToFactSheet(archiveId: string): void {
    if (!this.activeFactSheet) {
      console.warn('No active fact sheet to persist archive to');
      return;
    }

    const request = {
      embeddingModelSource: 'archive',
      embeddingArchiveId: archiveId
    };

    this.factSheetService.updateSheet(this.activeFactSheet.id, request).subscribe({
      next: (updatedSheet) => {
        console.log(`Persisted archive '${archiveId}' to fact sheet '${this.activeFactSheet?.name}'`);
        if (updatedSheet) {
          this.activeFactSheet = updatedSheet;
        }
        // Notify registry service so the model status indicator refreshes
        this.modelRegistryService.notifyRegistryRefreshed();
      },
      error: (err) => {
        console.error('Failed to persist archive to fact sheet:', err);
      }
    });
  }

  unloadArchive(): void {
    this.archiveService.unloadArchive().subscribe({
      next: () => {
        this.showSnackbar('Archive unloaded');
      },
      error: () => {
        this.showSnackbar('Failed to unload archive', true);
      }
    });
  }

  extractModel(model: ArchiveModelInfo): void {
    this.extractingModel = model.modelId;
    this.archiveService.extractModel(model.modelId).subscribe({
      next: (result) => {
        this.extractingModel = null;
        if (result.success) {
          this.showSnackbar(`Extracted ${model.modelId} to registry`);
          // Notify registry of changes
          this.modelRegistryService.notifyRegistryRefreshed();
        } else {
          this.showSnackbar(result.error || 'Failed to extract model', true);
        }
      },
      error: () => {
        this.extractingModel = null;
        this.showSnackbar('Failed to extract model', true);
      }
    });
  }

  extractAllModels(): void {
    if (!this.archiveModels.length) return;

    this.archiveLoading = true;
    const extractions = this.archiveModels.map(m =>
      this.archiveService.extractModel(m.modelId)
    );

    forkJoin(extractions).subscribe({
      next: (results) => {
        this.archiveLoading = false;
        const successCount = results.filter(r => r.success).length;
        this.showSnackbar(`Extracted ${successCount} of ${results.length} models`);
        // Notify registry of changes
        this.modelRegistryService.notifyRegistryRefreshed();
      },
      error: () => {
        this.archiveLoading = false;
        this.showSnackbar('Failed to extract models', true);
      }
    });
  }

  formatBytes(bytes: number): string {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // REMOTE STAGING METHODS (existing functionality)
  // ═══════════════════════════════════════════════════════════════════════════════

  loadRemoteStagingData(): void {
    this.stagingConfigService.loadConfigs().subscribe(() => {
      // After configs load, load remote registry if there's an active config
      if (this.activeConfig) {
        this.loadRemoteRegistry();
        this.loadActiveModels();
      }
    });
  }

  loadRemoteRegistry(): void {
    this.remoteRegistryLoading = true;
    this.stagingConfigService.getRemoteRegistry().subscribe({
      next: (registry) => {
        this.remoteRegistry = registry;
        this.remoteRegistryLoading = false;
        this.showConnectionRetryBanner = false; // Hide retry banner on success
        // Check which models are already downloaded locally
        this.checkDownloadedModels();
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to load remote registry:', err);
        this.remoteRegistry = null;
        this.remoteRegistryLoading = false;
        // Show retry banner when connection fails
        this.showConnectionRetryBanner = true;
        this.loadConnectionStatus(); // Refresh connection status
        this.cdr.detectChanges();
        const errorMsg = err.error?.error || err.message || 'Failed to connect to remote staging service';
        this.showSnackbar(errorMsg, true);
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CONNECTION RETRY METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load the current connection status from the backend.
   */
  loadConnectionStatus(): void {
    this.stagingConfigService.getConnectionStatus().subscribe({
      next: (status) => {
        this.connectionStatus = status;
        this.showConnectionRetryBanner = status.canRetry && !status.connected;
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to load connection status:', err);
      }
    });
  }

  /**
   * Retry the connection to the active staging service.
   * This is triggered by the user clicking the retry button.
   */
  retryConnection(): void {
    if (!this.activeConfig) {
      this.showSnackbar('No active staging service configured', true);
      return;
    }

    this.retryingConnection = true;
    this.cdr.detectChanges();

    this.stagingConfigService.retryConnection().subscribe({
      next: (result: RetryConnectionResult) => {
        this.retryingConnection = false;
        if (result.success) {
          this.showConnectionRetryBanner = false;
          this.showSnackbar(`Connection restored! Found ${result.modelCount || 0} models.`);
          // Reload all data after successful connection
          this.loadRemoteRegistry();
          this.loadActiveModels();
        } else {
          this.showConnectionRetryBanner = true;
          this.showSnackbar(`Connection failed: ${result.message}`, true);
        }
        this.loadConnectionStatus(); // Refresh connection status
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.retryingConnection = false;
        this.showConnectionRetryBanner = true;
        const errorMsg = err.error?.message || err.message || 'Connection retry failed';
        this.showSnackbar(errorMsg, true);
        this.loadConnectionStatus(); // Refresh connection status
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Retry connection and perform full discovery of models.
   */
  retryDiscover(): void {
    if (!this.activeConfig) {
      this.showSnackbar('No active staging service configured', true);
      return;
    }

    this.retryingConnection = true;
    this.cdr.detectChanges();

    this.stagingConfigService.retryDiscover().subscribe({
      next: (result: RetryDiscoverResult) => {
        this.retryingConnection = false;
        if (result.success) {
          this.showConnectionRetryBanner = false;
          const modelCount = result.modelCount || 0;
          const activeCount = result.activeModels ? Object.keys(result.activeModels).length : 0;
          this.showSnackbar(`Connected! Found ${modelCount} models (${activeCount} active).`);
          // Reload all data after successful discovery
          this.loadRemoteStagingData();
        } else {
          this.showConnectionRetryBanner = true;
          this.showSnackbar(`Discovery failed: ${result.error}`, true);
        }
        this.loadConnectionStatus(); // Refresh connection status
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.retryingConnection = false;
        this.showConnectionRetryBanner = true;
        const errorMsg = err.error?.error || err.message || 'Discovery failed';
        this.showSnackbar(errorMsg, true);
        this.loadConnectionStatus(); // Refresh connection status
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Get a human-readable time since the last connection attempt.
   */
  getTimeSinceLastAttempt(): string {
    if (!this.connectionStatus || this.connectionStatus.timeSinceLastAttemptMs < 0) {
      return 'Never';
    }
    const ms = this.connectionStatus.timeSinceLastAttemptMs;
    if (ms < 1000) {
      return 'Just now';
    } else if (ms < 60000) {
      return `${Math.floor(ms / 1000)} seconds ago`;
    } else if (ms < 3600000) {
      return `${Math.floor(ms / 60000)} minutes ago`;
    } else {
      return `${Math.floor(ms / 3600000)} hours ago`;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // WEBSOCKET REAL-TIME STATUS UPDATES
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Setup WebSocket subscription for real-time model status updates.
   * This allows the UI to update automatically when:
   * - Model is loaded/reloaded
   * - Staging connection is established/lost
   * - Model initialization completes
   */
  private setupWebSocketSubscription(): void {
    // Connect to WebSocket
    this.webSocketService.connect();

    // Subscribe to model status updates
    this.webSocketService.subscribeToModelStatus().pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (status: ModelStatusUpdate) => {
        this.handleModelStatusUpdate(status);
      },
      error: (err) => {
        console.error('Error in model status WebSocket:', err);
      }
    });
  }

  /**
   * Handle incoming model status updates from WebSocket.
   * This handles real-time updates including when async model loading completes.
   */
  private handleModelStatusUpdate(status: ModelStatusUpdate): void {
    // Update embedding status
    if (status.embedding) {
      const wasLoading = this.embeddingModelLoading;
      const wasReady = this.embeddingModelReady;
      const isNowReady = status.embedding.initialized && (status.embedding.dimensions || 0) > 0;
      const hasError = status.embedding.error && status.embedding.error.length > 0;
      const isFailed = status.embedding.source === 'FAILED';
      const hasActiveLoadingUI = this.downloadingModel || this.loadingModel;

      // Update loading state from WebSocket
      this.embeddingModelLoading = status.embedding.loading || false;
      this.embeddingOptimalBatchSize = status.embedding.optimalBatchSize || null;
      this.embeddingLoadingPhase = status.embedding.loadingPhase || null;
      this.embeddingLoadingMessage = status.embedding.loadingMessage || null;
      this.embeddingLoadingElapsedMs = status.embedding.loadingElapsedMs || 0;

      // Update ready state
      this.embeddingModelReady = isNowReady;

      // If model just became ready (finished loading)
      if (isNowReady && !wasReady) {
        // Model just became ready - update UI
        this.currentEmbeddingModel = status.embedding.modelId || null;

        // Clear the download/loading progress UI
        if (hasActiveLoadingUI) {
          this.downloadProgress = 100;
          this.downloadStatus = 'Complete!';
          this.cdr.detectChanges();

          // Reset UI state after showing completion
          setTimeout(() => {
            this.downloadingModel = null;
            this.loadingModel = null;
            this.downloadProgress = 0;
            this.downloadStatus = '';
            this.cdr.detectChanges();
          }, 1500);
        }

        // Refresh data and show success message
        this.loadEmbeddingStatus();
        const dims = status.embedding.dimensions ? ` (${status.embedding.dimensions}d)` : '';
        const modelName = status.embedding.modelId || 'Embedding model';
        this.showSnackbar(`${modelName} loaded successfully${dims}`);
      }

      // Detect model loading failure - multiple conditions to catch different scenarios:
      // 1. Was loading and now not loading with an error
      // 2. Source is FAILED and we have active loading UI
      // 3. Has error and not ready and we have active loading UI
      const loadingFailed = (
        (wasLoading && !status.embedding.loading && !isNowReady && hasError) ||
        (isFailed && hasActiveLoadingUI) ||
        (hasError && !isNowReady && hasActiveLoadingUI && !status.embedding.loading)
      );

      if (loadingFailed) {
        // Loading failed - clear progress UI and show error
        const errorMsg = status.embedding.error || 'Unknown error';
        console.error('Model loading failed:', errorMsg, 'source:', status.embedding.source);

        this.downloadingModel = null;
        this.loadingModel = null;
        this.downloadProgress = 0;
        this.downloadStatus = '';
        this.embeddingModelLoading = false;
        this.cdr.detectChanges();

        this.showSnackbar(`Model loading failed: ${errorMsg}`, true);

        // Refresh status to get latest state
        this.loadEmbeddingStatus();
      }
    }

    // Update staging connection status
    if (status.staging) {
      const wasConnected = this.connectionStatus?.connected;
      const isNowConnected = status.staging.connected;

      // Update connection status
      this.connectionStatus = {
        connected: status.staging.connected,
        attempted: status.staging.attempted,
        endpointUrl: status.staging.endpointUrl || null,
        lastError: status.staging.lastError || null,
        lastAttemptTimeMs: status.staging.lastAttemptTimeMs || 0,
        consecutiveFailures: status.staging.consecutiveFailures,
        canRetry: status.staging.canRetry,
        timeSinceLastAttemptMs: status.staging.timeSinceLastAttemptMs || 0
      };

      // Update retry banner visibility
      this.showConnectionRetryBanner = status.staging.canRetry && !status.staging.connected;

      // Show notification if connection state changed
      if (!wasConnected && isNowConnected) {
        this.showSnackbar('Staging connection established');
        // Refresh data when connection is restored
        this.loadRemoteStagingData();
      } else if (wasConnected && !isNowConnected && status.staging.attempted) {
        this.showConnectionRetryBanner = true;
      }
    }

    this.cdr.detectChanges();
  }

  /**
   * Check which models are downloaded locally.
   * Updates the downloadedModels set for ALL model types.
   */
  checkDownloadedModels(): void {
    const allModels = this.getActiveRemoteModels();
    allModels.forEach(model => {
      this.stagingConfigService.isModelDownloaded(model.modelId).subscribe({
        next: (response) => {
          if (response.downloaded) {
            this.downloadedModels.add(model.modelId);
          } else {
            this.downloadedModels.delete(model.modelId);
          }
          this.cdr.detectChanges();
        },
        error: () => {
          // Assume not downloaded on error
          this.downloadedModels.delete(model.modelId);
        }
      });
    });
  }

  /**
   * Check if a model is downloaded locally.
   * Works for ALL model types.
   */
  isModelDownloaded(modelId: string): boolean {
    return this.downloadedModels.has(modelId);
  }

  getStagedModels(): RemoteModelEntry[] {
    if (!this.remoteRegistry?.models) return [];
    return Object.entries(this.remoteRegistry.models)
      .filter(([_, model]) => model.status === 'staged')
      .map(([id, model]) => ({ ...model, modelId: id }));
  }

  getActiveRemoteModels(): RemoteModelEntry[] {
    if (!this.remoteRegistry?.models) {
      // Don't spam the console - this gets called frequently by change detection
      return [];
    }
    const entries = Object.entries(this.remoteRegistry.models);
    const activeModels = entries
      .filter(([_, model]) => model.status === 'active')
      .map(([id, model]) => ({ ...model, modelId: id }));
    return activeModels;
  }

  promoteModel(modelId: string): void {
    this.promotingModel = modelId;
    this.stagingConfigService.promoteRemoteModel(modelId).subscribe({
      next: (response) => {
        this.promotingModel = null;
        if (response.success) {
          this.showSnackbar(`Model ${modelId} promoted to active`);
          this.loadRemoteRegistry();
        } else {
          this.showSnackbar(response.error || 'Failed to promote model', true);
        }
      },
      error: () => {
        this.promotingModel = null;
        this.showSnackbar('Failed to promote model', true);
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ACTIVE MODEL METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  loadActiveModels(): void {
    if (!this.activeConfig) {
      return;
    }
    this.stagingConfigService.getActiveModels().subscribe({
      next: (response) => {
        this.activeModelsByType = response.active || {};
      },
      error: () => {
        this.activeModelsByType = {};
      }
    });
  }

  activateModel(modelId: string): void {
    this.activatingModel = modelId;
    this.stagingConfigService.activateModel(modelId).subscribe({
      next: () => {
        this.activatingModel = null;
        this.showSnackbar(`Model ${modelId} activated`);
        this.loadRemoteRegistry();
        this.loadActiveModels();
      },
      error: () => {
        this.activatingModel = null;
        this.showSnackbar('Failed to activate model', true);
      }
    });
  }

  /**
   * Download and load a model from the remote staging service.
   * Downloads the model files to local storage, then loads into the embedding engine asynchronously.
   *
   * The model loading happens in the background - progress updates come via WebSocket.
   */
  downloadAndLoadModel(modelId: string): void {
    if (!this.activeConfig) {
      this.showSnackbar('No active remote staging service configured', true);
      return;
    }

    // Set download state
    this.downloadingModel = modelId;
    this.loadingModel = modelId;
    this.downloadProgress = 0;
    this.downloadStatus = 'Downloading model files...';
    this.cdr.detectChanges();

    // Cancel any existing download
    if (this.downloadSubscription) {
      this.downloadSubscription.unsubscribe();
    }

    this.downloadSubscription = this.stagingConfigService.downloadAndLoadModel(modelId).subscribe({
      next: (response) => {
        // Mark model as downloaded locally (works for ALL model types)
        if (response.downloadSuccess) {
          this.downloadedModels.add(modelId);
        }

        // Handle async loading response - model loading happens in background
        if (response.loading) {
          // Download complete, model loading in background
          this.downloadProgress = 50;
          this.downloadStatus = 'Model downloaded. Loading in background...';
          this.embeddingModelLoading = true;
          this.cdr.detectChanges();

          // Show notification that loading is happening in background
          this.showSnackbar(`Model ${modelId} downloaded. Loading in background - check progress below.`);

          // Persist the model configuration to the fact sheet with 'staging' source
          this.persistModelToFactSheet(modelId, 'staging');

          // Don't reset loading state - WebSocket handleModelStatusUpdate will do that
          // when model loading completes or fails
          return;
        }

        // Handle synchronous completion (fallback mode or non-dense encoder)
        this.downloadProgress = 100;
        this.downloadStatus = response.success ? 'Complete!' : 'Failed';
        this.cdr.detectChanges();

        // Reset state after a short delay
        setTimeout(() => {
          this.downloadingModel = null;
          this.loadingModel = null;
          this.downloadProgress = 0;
          this.downloadStatus = '';
          this.cdr.detectChanges();
        }, 1000);

        if (response.success) {
          if (response.loadSuccess) {
            this.currentEmbeddingModel = response.currentModel;
            const dims = response.dimensions ? ` (${response.dimensions}d)` : '';
            this.showSnackbar(`Model ${modelId} downloaded and loaded successfully${dims}`);

            // Persist the model configuration to the fact sheet with 'staging' source
            this.persistModelToFactSheet(modelId, 'staging');
          } else {
            // Downloaded but not loaded (different model type or embedding not configured)
            const msg = response.loadMessage || response.loadError || 'Model downloaded but not loaded';
            this.showSnackbar(`Model ${modelId} downloaded. ${msg}`);
          }

          // Refresh all relevant data
          this.refreshAllData();
        } else {
          const phase = response.phase === 'download' ? 'download' : 'load';
          this.showSnackbar(`Failed to ${phase} model: ${response.error}`, true);
        }
      },
      error: (err) => {
        this.downloadingModel = null;
        this.loadingModel = null;
        this.downloadProgress = 0;
        this.downloadStatus = '';
        this.embeddingModelLoading = false;
        this.cdr.detectChanges();
        this.showSnackbar(`Failed to download model: ${err.error?.error || err.message}`, true);
      }
    });
  }

  /**
   * Cancel an ongoing model download.
   */
  cancelDownload(): void {
    if (this.downloadSubscription) {
      this.downloadSubscription.unsubscribe();
      this.downloadSubscription = null;
    }
    this.downloadingModel = null;
    this.loadingModel = null;
    this.downloadProgress = 0;
    this.downloadStatus = '';
    this.cdr.detectChanges();
    this.showSnackbar('Download cancelled');
  }

  /**
   * Refresh all data after a model operation.
   */
  refreshAllData(): void {
    this.loadEmbeddingStatus();
    this.loadActiveModels();
    this.loadRemoteRegistry();
    this.cdr.detectChanges();
  }

  /**
   * Legacy method - switch to already downloaded local model.
   */
  loadModel(modelId: string): void {
    this.loadingModel = modelId;
    this.stagingConfigService.loadModel(modelId).subscribe({
      next: (response) => {
        this.loadingModel = null;
        if (response.success) {
          this.currentEmbeddingModel = response.currentModel;
          this.showSnackbar(`Model ${modelId} loaded successfully (${response.dimensions}d)`);
          this.loadEmbeddingStatus();

          // Persist the model configuration to the fact sheet with 'staging' source
          this.persistModelToFactSheet(modelId, 'staging');
        } else {
          this.showSnackbar(`Failed to load model: ${response.error}`, true);
        }
      },
      error: (err) => {
        this.loadingModel = null;
        this.showSnackbar(`Failed to load model: ${err.error?.error || err.message}`, true);
      }
    });
  }

  /**
   * Load the current embedding model status.
   */
  loadEmbeddingStatus(): void {
    this.stagingConfigService.getEmbeddingStatus().subscribe({
      next: (status) => {
        this.embeddingModelStatus = status;
        if (status.available) {
          this.currentEmbeddingModel = status.activeModelId || status.modelId;
        }
      },
      error: () => {
        this.embeddingModelStatus = null;
        this.currentEmbeddingModel = null;
      }
    });
  }

  /**
   * Refresh and reload the embedding model.
   */
  refreshAndReloadModel(): void {
    this.loadingModel = 'refreshing';
    this.stagingConfigService.refreshAndReloadModel().subscribe({
      next: (response) => {
        this.loadingModel = null;
        if (response.success) {
          this.currentEmbeddingModel = response.currentModel;
          this.showSnackbar('Model source refreshed and model reloaded');
          this.loadEmbeddingStatus();
          this.loadActiveModels();
        } else {
          this.showSnackbar('Failed to refresh model', true);
        }
      },
      error: (err) => {
        this.loadingModel = null;
        this.showSnackbar(`Failed to refresh: ${err.error?.error || err.message}`, true);
      }
    });
  }

  /**
   * Check if a model is currently loaded in the application.
   */
  isModelLoaded(modelId: string): boolean {
    return this.currentEmbeddingModel === modelId;
  }

  isActiveModel(modelId: string): boolean {
    return Object.values(this.activeModelsByType).includes(modelId);
  }

  getActiveModelForType(type: string): string | null {
    // Handle both 'encoder' and 'dense_encoder' naming conventions
    if (type === 'dense_encoder') {
      return this.activeModelsByType['dense_encoder'] || this.activeModelsByType['encoder'] || null;
    }
    if (type === 'encoder') {
      return this.activeModelsByType['encoder'] || this.activeModelsByType['dense_encoder'] || null;
    }
    return this.activeModelsByType[type] || null;
  }

  getModelStatus(model: RemoteModelEntry): string {
    if (model.status === 'active') return 'active';
    if (model.status === 'available') return 'available';
    if (model.status === 'unavailable') return 'unavailable';
    return model.status || 'available';
  }

  getTypeLabel(type: string): string {
    switch (type) {
      case 'encoder':
      case 'dense_encoder': return 'Dense Encoder';
      case 'sparse_encoder': return 'Sparse Encoder';
      case 'cross_encoder': return 'Cross-Encoder';
      default: return type;
    }
  }

  getTypeIcon(type: string): string {
    switch (type) {
      case 'encoder':
      case 'dense_encoder': return 'text_fields';
      case 'sparse_encoder': return 'scatter_plot';
      case 'cross_encoder': return 'compare_arrows';
      default: return 'memory';
    }
  }

  private initForm(): void {
    this.configForm = this.fb.group({
      name: ['', [Validators.required, Validators.minLength(1), Validators.maxLength(255)]],
      endpointUrl: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/)]],
      apiKey: [''],
      active: [false],
      connectionTimeoutMs: [5000, [Validators.required, Validators.min(1000), Validators.max(60000)]],
      readTimeoutMs: [30000, [Validators.required, Validators.min(1000), Validators.max(300000)]],
      autoSync: [false],
      syncIntervalMinutes: [60, [Validators.required, Validators.min(1), Validators.max(1440)]],
      description: ['']
    });
  }

  showAddForm(): void {
    this.editingConfig = null;
    this.configForm.reset({
      name: '',
      endpointUrl: '',
      apiKey: '',
      active: this.configs.length === 0,
      connectionTimeoutMs: 5000,
      readTimeoutMs: 30000,
      autoSync: false,
      syncIntervalMinutes: 60,
      description: ''
    });
    this.showForm = true;
  }

  showEditForm(config: StagingServiceConfig): void {
    this.editingConfig = config;
    this.configForm.patchValue({
      name: config.name,
      endpointUrl: config.endpointUrl,
      apiKey: config.apiKey || '',
      active: config.active,
      connectionTimeoutMs: config.connectionTimeoutMs,
      readTimeoutMs: config.readTimeoutMs,
      autoSync: config.autoSync,
      syncIntervalMinutes: config.syncIntervalMinutes,
      description: config.description || ''
    });
    this.showForm = true;
  }

  cancelForm(): void {
    this.showForm = false;
    this.editingConfig = null;
    this.configForm.reset();
  }

  saveConfig(): void {
    if (this.configForm.invalid) {
      this.showSnackbar('Please fix form errors', true);
      return;
    }

    const dto: StagingServiceConfigDto = {
      name: this.configForm.value.name,
      endpointUrl: this.configForm.value.endpointUrl,
      apiKey: this.configForm.value.apiKey || undefined,
      active: this.configForm.value.active,
      connectionTimeoutMs: this.configForm.value.connectionTimeoutMs,
      readTimeoutMs: this.configForm.value.readTimeoutMs,
      autoSync: this.configForm.value.autoSync,
      syncIntervalMinutes: this.configForm.value.syncIntervalMinutes,
      description: this.configForm.value.description || undefined
    };

    if (this.editingConfig) {
      this.stagingConfigService.updateConfig(this.editingConfig.id!, dto).subscribe({
        next: () => {
          this.showSnackbar('Configuration updated successfully');
          this.cancelForm();
        },
        error: (err) => {
          this.showSnackbar(err.error?.error || 'Failed to update configuration', true);
        }
      });
    } else {
      this.stagingConfigService.createConfig(dto).subscribe({
        next: () => {
          this.showSnackbar('Configuration created successfully');
          this.cancelForm();
        },
        error: (err) => {
          this.showSnackbar(err.error?.error || 'Failed to create configuration', true);
        }
      });
    }
  }

  deleteConfig(config: StagingServiceConfig): void {
    const dialogData: ConfirmDialogData = {
      title: 'Delete Configuration',
      message: `Are you sure you want to delete "${config.name}"?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'delete'
    };

    const dialogRef = this.dialog.open(ConfirmDialogComponent, { data: dialogData });
    dialogRef.afterClosed()
      .pipe(filter(confirmed => confirmed === true))
      .subscribe(() => {
        this.stagingConfigService.deleteConfig(config.id!).subscribe({
          next: () => {
            this.showSnackbar('Configuration deleted');
          },
          error: (err) => {
            this.showSnackbar(err.error?.error || 'Failed to delete configuration', true);
          }
        });
      });
  }

  activateConfig(config: StagingServiceConfig): void {
    this.stagingConfigService.activateConfig(config.id!).subscribe({
      next: () => {
        this.showSnackbar(`"${config.name}" is now the active staging service`);
        // Also persist the source as 'staging' to the fact sheet
        this.persistSourceToFactSheet('staging');
      },
      error: (err) => {
        this.showSnackbar(err.error?.error || 'Failed to activate configuration', true);
      }
    });
  }

  testConnection(config: StagingServiceConfig): void {
    this.testingId = config.id!;

    this.stagingConfigService.testConnection(config.id!).subscribe({
      next: (result: ConnectionTestResult) => {
        this.testingId = null;
        if (result.success) {
          this.showSnackbar(
            `Connected! Registry has ${result.modelCount} models (version: ${result.version})`
          );
        } else {
          this.showSnackbar(`Connection failed: ${result.message}`, true);
        }
      },
      error: () => {
        this.testingId = null;
        this.showSnackbar('Connection test failed', true);
      }
    });
  }

  refresh(): void {
    this.stagingConfigService.refresh();
  }

  formatDate(dateString?: string): string {
    if (!dateString) return 'Never';
    const date = new Date(dateString);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
  }

  getStatusIcon(config: StagingServiceConfig): string {
    if (!config.verified) return 'help_outline';
    return config.lastError ? 'error' : 'check_circle';
  }

  getStatusColor(config: StagingServiceConfig): string {
    if (!config.verified && !config.lastVerifiedAt) return '';
    return config.verified ? 'status-success' : 'status-error';
  }

  /**
   * Refresh all data from archives and remote staging.
   */
  refreshAll(): void {
    this.loadArchiveData();
    this.loadRemoteStagingData();
  }

  /**
   * Toggle the collapsed state of the Remote Staging panel.
   */
  toggleRemoteStagingCollapse(): void {
    this.remoteStagingCollapsed = !this.remoteStagingCollapsed;
  }

  /**
   * Force reload models - retries connection and reloads the embedding model.
   * This is useful when the staging service was offline and is now back online.
   */
  forceReloadModels(): void {
    this.forceReloading = true;
    this.cdr.detectChanges();

    // If we have an active staging config, retry connection first
    if (this.activeConfig) {
      this.stagingConfigService.retryDiscover().subscribe({
        next: (result) => {
          if (result.success) {
            // Connection restored, now reload the model
            this.stagingConfigService.refreshAndReloadModel().subscribe({
              next: (reloadResult) => {
                this.forceReloading = false;
                if (reloadResult.success) {
                  this.showSnackbar('Models reloaded successfully!');
                  this.refreshAllData();
                } else {
                  this.showSnackbar('Connected to staging but model reload failed', true);
                }
                this.cdr.detectChanges();
              },
              error: (err) => {
                this.forceReloading = false;
                this.showSnackbar(`Model reload failed: ${err.error?.error || err.message}`, true);
                this.cdr.detectChanges();
              }
            });
          } else {
            this.forceReloading = false;
            this.showSnackbar(`Staging connection failed: ${result.error || result.message}`, true);
            this.cdr.detectChanges();
          }
        },
        error: (err) => {
          this.forceReloading = false;
          this.showSnackbar(`Connection retry failed: ${err.error?.error || err.message}`, true);
          this.cdr.detectChanges();
        }
      });
    } else {
      // No staging config, just try to reload from registry/archive
      this.stagingConfigService.refreshAndReloadModel().subscribe({
        next: (reloadResult) => {
          this.forceReloading = false;
          if (reloadResult.success) {
            this.showSnackbar('Models reloaded successfully!');
            this.refreshAllData();
          } else {
            this.showSnackbar('Model reload failed', true);
          }
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.forceReloading = false;
          this.showSnackbar(`Model reload failed: ${err.error?.error || err.message}`, true);
          this.cdr.detectChanges();
        }
      });
    }
  }

  /**
   * Refresh connected applications status.
   */
  refreshConnectedApps(): void {
    this.loadingConnectedApps = true;
    this.stagingConfigService.loadConfigs().subscribe({
      next: () => {
        this.loadingConnectedApps = false;
      },
      error: () => {
        this.loadingConnectedApps = false;
      }
    });
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: isError ? 6000 : 4000,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // GRAPH OPTIMIZATION METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load the current graph optimization status.
   */
  loadGraphOptimizationStatus(): void {
    this.processingSettingsService.getGraphOptimizationStatus().subscribe({
      next: (status) => {
        this.graphOptimizationStatus = status;
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to load graph optimization status:', err);
        this.graphOptimizationStatus = { available: false, message: 'Failed to load status' };
      }
    });
  }

  /**
   * Optimize and save the current model graph.
   * This creates a pre-optimized model file that can be registered for faster startup.
   */
  optimizeAndSaveModel(): void {
    if (!this.graphOptimizationStatus.embeddingModelLoaded) {
      this.showSnackbar('No embedding model loaded. Load a model first.', true);
      return;
    }

    this.isOptimizing = true;
    this.lastOptimizationResult = null;
    this.cdr.detectChanges();

    this.processingSettingsService.optimizeAndSaveModel().subscribe({
      next: (result) => {
        this.isOptimizing = false;
        this.lastOptimizationResult = result;

        if (result.success) {
          const timeSeconds = result.optimizationTimeMs ? (result.optimizationTimeMs / 1000).toFixed(1) : '?';
          this.showSnackbar(`Model optimized and saved in ${timeSeconds}s`);

          // Log next steps to console
          if (result.nextSteps) {
            console.log('Optimization next steps:', result.nextSteps);
          }
        } else {
          this.showSnackbar(`Optimization failed: ${result.error || result.message}`, true);
        }
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.isOptimizing = false;
        const errorMsg = err.error?.error || err.error?.message || err.message || 'Optimization failed';
        this.showSnackbar(errorMsg, true);
        this.lastOptimizationResult = { success: false, error: errorMsg };
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Restore the model to its unoptimized state.
   * This uses the backup created during optimization.
   */
  restoreUnoptimizedModel(modelId: string): void {
    this.isOptimizing = true;
    this.optimizingModelId = modelId;
    this.cdr.detectChanges();

    this.stagingConfigService.restoreUnoptimized(modelId).subscribe({
      next: (result) => {
        this.isOptimizing = false;
        this.optimizingModelId = null;
        if (result.success) {
          this.showSnackbar('Model restored to unoptimized state');
          // Refresh status after restore
          this.loadGraphOptimizationStatus();
          this.loadLocalRegistry();
          this.lastOptimizationResult = null;
        } else {
          this.showSnackbar(`Restore failed: ${result.error || result.message}`, true);
        }
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.isOptimizing = false;
        this.optimizingModelId = null;
        const errorMsg = err.error?.error || err.error?.message || err.message || 'Restore failed';
        this.showSnackbar(errorMsg, true);
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Optimize a model from the catalog.
   * This creates a backup and saves the optimized version in-place.
   */
  optimizeModelFromCatalog(modelId: string): void {
    this.optimizingModelId = modelId;
    this.cdr.detectChanges();

    this.stagingConfigService.optimizeModel(modelId).subscribe({
      next: (result: OptimizeResult) => {
        this.optimizingModelId = null;
        if (result.success) {
          const timeSeconds = result.optimizationTimeMs ? (result.optimizationTimeMs / 1000).toFixed(1) : '?';
          this.showSnackbar(`Model ${modelId} optimized in ${timeSeconds}s`);
          // Refresh the registry to show updated status
          this.loadLocalRegistry();
          this.loadGraphOptimizationStatus();
        } else {
          this.showSnackbar(`Optimization failed: ${result.error || result.message}`, true);
        }
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.optimizingModelId = null;
        const errorMsg = err.error?.error || err.error?.message || err.message || 'Optimization failed';
        this.showSnackbar(errorMsg, true);
        this.cdr.detectChanges();
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // LOCAL REGISTRY METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load the local model registry.
   */
  loadLocalRegistry(): void {
    this.localRegistryLoading = true;
    this.stagingConfigService.getLocalRegistry().subscribe({
      next: (registry) => {
        this.localRegistry = registry;
        this.localRegistryLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to load local registry:', err);
        this.localRegistry = null;
        this.localRegistryLoading = false;
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Check if a model is optimized.
   */
  isModelOptimized(model: LocalModelEntry): boolean {
    return model.metadata?.optimized === true;
  }

  /**
   * Check if a model has an unoptimized backup.
   */
  hasUnoptimizedBackup(model: LocalModelEntry): boolean {
    return this.isModelOptimized(model) && !!model.metadata?.unoptimizedBackupFile;
  }

  /**
   * Get optimization info for display.
   */
  getOptimizationInfo(model: LocalModelEntry): string {
    if (!this.isModelOptimized(model)) {
      return 'Not optimized';
    }
    const timeMs = model.metadata?.optimizationTimeMs;
    if (timeMs) {
      return `Optimized (${(timeMs / 1000).toFixed(1)}s)`;
    }
    return 'Optimized';
  }
}
