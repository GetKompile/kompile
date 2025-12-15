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

import { Component, OnInit, OnDestroy, ChangeDetectorRef, ViewChild, AfterViewInit, ElementRef } from '@angular/core';
import { DocumentService } from '../../services/document.service';
import { AnseriniService } from '../../services/anserini.service';
import { WebSocketService, WebSocketConnectionState } from '../../services/websocket.service';
import { IngestEventService } from '../../services/ingest-event.service';
import {
  AddUrlRequest,
  FileUploadResponse,
  SimpleMessageResponse,
  LoaderInfo,
  ChunkerInfo,
  BatchProcessRequest,
  BatchLoadRequestItem,
  DocumentSourceType,
  BatchProcessResponse,
  BatchProcessResponseDetails,
  AddSourceDialogResult,
  DocumentSummary,
  DebuggerStatus,
  DebugAnalysisResult,
  TestUploadResponse,
  AsyncUploadResponse,
  BatchAsyncUploadResponse,
  IngestProgressUpdate,
  IngestPhase,
  IngestStatus,
  IngestLogEntry,
  ProcessingSettings,
  ProcessingSettingsUpdate,
  UploadedFileInfo,
  TaskEnvironmentResponse
} from '../../models/api-models';
import { ProcessingSettingsService } from '../../services/processing-settings.service';
import { AdaptivePerformanceService, AdaptiveMetrics, BatchAdjustment } from '../../services/adaptive-performance.service';
import { BatchConfigService } from '../../services/batch-config.service';
import { BatchSizeConfigResponse, EmbeddingModelInfo } from '../../models/batch-config.models';
import { Subscription } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource } from '@angular/material/table';
import { MatPaginator } from '@angular/material/paginator';
import { MatSort } from '@angular/material/sort';
import { AddSourceDialogComponent } from './add-source-dialog/add-source-dialog.component';
import { forkJoin, of, Observable, interval } from 'rxjs';
import { catchError, finalize, map, startWith } from 'rxjs/operators';

export interface ConfiguredSourceElement {
  id: number;
  path: string;
  type: 'Property' | 'Upload Path';
}

export interface UploadedFileElement {
  id: number;
  name: string;
}

type UploadedFilesData = { uploaded_files_location: string; files: UploadedFileInfo[] };

export interface WarmupStatus {
  enabled: boolean;
  complete: boolean;
  ready: boolean;
  embeddingReady: boolean;
  chunkerReady: boolean;
  embeddingWarmupTimeMs: number;
  chunkerWarmupTimeMs: number;
  totalWarmupTimeMs: number;
  // New fields for better progress feedback
  currentPhase: string;  // 'idle', 'downloading', 'loading_embedding', 'warming_embedding', 'loading_chunker', 'warming_chunker'
  modelDownloadRequired: boolean;
  elapsedTimeMs: number;
}

@Component({
  standalone: false,
  selector: 'app-document-manager',
  templateUrl: './document-manager.component.html',
  styleUrls: ['./document-manager.component.css']
})
export class DocumentManagerComponent implements OnInit, AfterViewInit, OnDestroy {
  configuredSourcesDataSource = new MatTableDataSource<ConfiguredSourceElement>();
  displayedColumnsConfiguredSources: string[] = ['id', 'path', 'type'];

  uploadedFilesDataSource = new MatTableDataSource<UploadedFileElement>();
  displayedColumnsUploadedFiles: string[] = ['id', 'name', 'actions'];

  @ViewChild('configuredSourcesPaginator') configuredSourcesPaginator!: MatPaginator;
  @ViewChild('configuredSourcesSort') configuredSourcesSort!: MatSort;
  @ViewChild('uploadedFilesPaginator') uploadedFilesPaginator!: MatPaginator;
  @ViewChild('uploadedFilesSort') uploadedFilesSort!: MatSort;

  availableLoaders: LoaderInfo[] = [];
  availableChunkers: ChunkerInfo[] = [];

  private _configuredSources: string[] = [];
  private _uploadedFiles: UploadedFileInfo[] = [];
  uploadedFilesLocation: string = 'N/A';

  batchSourcePaths: string = '';
  batchSelectedLoader: string = '';
  isBatchProcessing: boolean = false;
  batchResults: BatchProcessResponseDetails | null = null;
  batchResultPaths: string[] = [];

  isLoading: boolean = false;

  showLoadersSpinner: boolean = false;
  showNoLoadersMessage: boolean = false;
  showLoadersList: boolean = false;
  showChunkersSpinner: boolean = false;
  showNoChunkersMessage: boolean = false;
  showChunkersList: boolean = false;
  showConfiguredSourcesSpinner: boolean = false;
  showNoConfiguredSourcesMessage: boolean = false;
  showConfiguredSourcesTable: boolean = false;
  showUploadedFilesSpinner: boolean = false;
  showNoUploadedFilesMessage: boolean = false;
  showUploadedFilesTable: boolean = false;
  showBatchResults: boolean = false;

  // Debugger Properties
  debuggerStatus: DebuggerStatus | null = null;
  isLoadingDebuggerStatus: boolean = false;
  selectedFileForDebug: string = '';
  selectedLoaderForDebug: string = '';
  selectedChunkerForDebug: string = '';
  debugAnalysisResult: DebugAnalysisResult | null = null;
  isAnalyzingFile: boolean = false;
  showDebugStatusCard: boolean = false;
  showDebugAnalysisCard: boolean = false;
  debugTestUploadFile: File | null = null;
  isTestingUpload: boolean = false;

  // Async upload tracking
  activeUploads: Map<string, IngestProgressUpdate> = new Map();
  activeUploadsArray: IngestProgressUpdate[] = []; // Array for template binding - more reliable change detection
  wsConnectionState: WebSocketConnectionState = WebSocketConnectionState.DISCONNECTED;
  expandedPipelines: Set<string> = new Set(); // Track which task pipelines are expanded
  expandedBatchDetails: Set<string> = new Set(); // Track which batch details are expanded
  expandedSubprocessDetails: Set<string> = new Set(); // Track which subprocess details are expanded
  expandedSubprocessLogs: Set<string> = new Set(); // Track which subprocess logs are expanded
  taskLogs: Map<string, IngestLogEntry[]> = new Map(); // Store logs per task for display
  private cancelledTaskIds: Set<string> = new Set(); // Track locally-cancelled tasks to prevent polling from overwriting
  private logsSubscription: Subscription | null = null;
  private wsSubscription: Subscription | null = null;
  private connectionSubscription: Subscription | null = null;
  private settingsSubscription: Subscription | null = null;
  private taskPollingSubscription: Subscription | null = null;
  useAsyncUpload: boolean = true; // Toggle for async vs sync upload

  // Processing settings
  processingSettings: ProcessingSettings | null = null;
  editableSettings: ProcessingSettingsUpdate = {
    maxConcurrentJobs: 4,
    indexBatchSize: 50,
    memoryThresholdPercent: 80,
    adaptiveBatchSize: true
  };
  hasSettingsChanged: boolean = false;
  isApplyingSettings: boolean = false;
  isGCRunning: boolean = false;

  // Warmup status
  warmupStatus: WarmupStatus | null = null;
  isWarmingUp: boolean = false;
  private warmupPollingSubscription: Subscription | null = null;

  // Adaptive performance metrics
  adaptiveMetrics: AdaptiveMetrics | null = null;
  adaptiveAdjustments: BatchAdjustment[] = [];
  adaptiveModeActive: boolean = false;
  showAdaptiveMetrics: boolean = false;
  private adaptiveMetricsSubscription: Subscription | null = null;
  private adaptiveAdjustmentsSubscription: Subscription | null = null;
  private adaptiveActiveSubscription: Subscription | null = null;

  // Embedding batch size config (from benchmark results)
  currentBatchConfig: BatchSizeConfigResponse | null = null;
  currentEmbeddingModel: EmbeddingModelInfo | null = null;
  hasBenchmarkConfig: boolean = false;

  // Task environment tracking (ND4J config captured at job start)
  taskEnvironments: Map<string, TaskEnvironmentResponse> = new Map();
  expandedEnvironments: Set<string> = new Set(); // Track which task environments are expanded

  constructor(
    private documentService: DocumentService,
    private anseriniService: AnseriniService,
    private webSocketService: WebSocketService,
    private ingestEventService: IngestEventService,
    private processingSettingsService: ProcessingSettingsService,
    private adaptivePerformanceService: AdaptivePerformanceService,
    private batchConfigService: BatchConfigService,
    public dialog: MatDialog,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) { }

  ngOnInit(): void {
    this.refreshAllData();
    this.loadDebuggerStatus();
    // Load active tasks immediately on page load/reload
    this.loadActiveIngestTasks();
    this.initializeWebSocket();
    this.loadProcessingSettings();
    this.startSettingsPolling();
    this.startTaskPolling();
    this.checkWarmupStatus();
    this.initializeAdaptiveMetrics();
    this.loadBatchConfig();
  }

  ngOnDestroy(): void {
    this.wsSubscription?.unsubscribe();
    this.logsSubscription?.unsubscribe();
    this.connectionSubscription?.unsubscribe();
    this.settingsSubscription?.unsubscribe();
    this.taskPollingSubscription?.unsubscribe();
    this.warmupPollingSubscription?.unsubscribe();
    this.adaptiveMetricsSubscription?.unsubscribe();
    this.adaptiveAdjustmentsSubscription?.unsubscribe();
    this.adaptiveActiveSubscription?.unsubscribe();
    this.webSocketService.disconnect();
  }

  private initializeAdaptiveMetrics(): void {
    // Subscribe to adaptive metrics
    this.adaptiveMetricsSubscription = this.adaptivePerformanceService.metrics$.subscribe(metrics => {
      this.adaptiveMetrics = metrics;
      this.cdr.detectChanges();
    });

    // Subscribe to adjustments
    this.adaptiveAdjustmentsSubscription = this.adaptivePerformanceService.adjustments$.subscribe(adjustments => {
      this.adaptiveAdjustments = adjustments;
      this.cdr.detectChanges();
    });

    // Subscribe to active state
    this.adaptiveActiveSubscription = this.adaptivePerformanceService.isActive$.subscribe(active => {
      this.adaptiveModeActive = active;
      // Show metrics panel when adaptive mode becomes active
      if (active) {
        this.showAdaptiveMetrics = true;
      }
      this.cdr.detectChanges();
    });

    // Fetch initial backend status
    this.adaptivePerformanceService.fetchBackendStatus().subscribe({
      error: (err) => console.warn('Could not fetch adaptive status:', err)
    });
  }

  /**
   * Load the current batch size configuration from the backend.
   * This fetches the persisted benchmark results if available.
   */
  private loadBatchConfig(): void {
    this.batchConfigService.getAvailableModels().subscribe({
      next: (models) => {
        // Find the first loaded model (the active one)
        const loadedModel = models.find(m => m.isLoaded);
        if (loadedModel) {
          this.currentEmbeddingModel = loadedModel;
          this.currentBatchConfig = loadedModel.batchConfig;
          // Check if this is a custom/benchmark config (has runtime override)
          this.hasBenchmarkConfig = loadedModel.batchConfig?.hasRuntimeOverride ?? false;
          this.cdr.detectChanges();
        }
      },
      error: (err) => {
        console.warn('Could not load batch config:', err);
      }
    });
  }

  /**
   * Get the currently configured optimal batch size.
   */
  getCurrentOptimalBatchSize(): number | null {
    return this.currentBatchConfig?.currentOptimalBatchSize ?? null;
  }

  /**
   * Get the effective (runtime) batch size.
   */
  getEffectiveBatchSize(): number | null {
    return this.currentBatchConfig?.calculatedEffectiveBatchSize ?? null;
  }

  toggleAdaptiveMetricsPanel(): void {
    this.showAdaptiveMetrics = !this.showAdaptiveMetrics;
  }

  getAdaptiveAdjustmentIcon(direction: string): string {
    return direction === 'INCREASE' ? 'trending_up' : 'trending_down';
  }

  getAdaptiveAdjustmentClass(direction: string): string {
    return direction === 'INCREASE' ? 'adjustment-increase' : 'adjustment-decrease';
  }

  formatAdjustmentTime(timestamp: Date): string {
    const date = new Date(timestamp);
    return date.toLocaleTimeString();
  }

  private loadProcessingSettings(): void {
    this.processingSettingsService.getSettings().subscribe({
      next: (settings) => {
        this.processingSettings = settings;
        this.syncEditableSettings();
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.warn('Could not load processing settings:', err);
      }
    });
  }

  private startSettingsPolling(): void {
    // Poll settings every 5 seconds to keep memory status updated
    this.settingsSubscription = this.processingSettingsService.pollSettings(5000).subscribe({
      next: (settings) => {
        this.processingSettings = settings;
        // Don't overwrite editable settings if user has made changes
        if (!this.hasSettingsChanged) {
          this.syncEditableSettings();
        }
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.warn('Settings polling error:', err);
      }
    });
  }

  private syncEditableSettings(): void {
    if (this.processingSettings) {
      this.editableSettings = {
        maxConcurrentJobs: this.processingSettings.maxConcurrentJobs,
        indexBatchSize: this.processingSettings.indexBatchSize,
        memoryThresholdPercent: this.processingSettings.memoryThresholdPercent,
        adaptiveBatchSize: this.processingSettings.adaptiveBatchSize
      };
    }
  }

  onSettingsChange(): void {
    if (!this.processingSettings) return;

    this.hasSettingsChanged =
      this.editableSettings.maxConcurrentJobs !== this.processingSettings.maxConcurrentJobs ||
      this.editableSettings.indexBatchSize !== this.processingSettings.indexBatchSize ||
      this.editableSettings.memoryThresholdPercent !== this.processingSettings.memoryThresholdPercent ||
      this.editableSettings.adaptiveBatchSize !== this.processingSettings.adaptiveBatchSize;
  }

  applySettings(): void {
    if (!this.hasSettingsChanged) return;

    this.isApplyingSettings = true;
    this.processingSettingsService.updateSettings(this.editableSettings).subscribe({
      next: (settings) => {
        this.processingSettings = settings;
        this.hasSettingsChanged = false;
        this.isApplyingSettings = false;
        this.showSnackbar('Processing settings updated successfully');
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.isApplyingSettings = false;
        this.showSnackbar('Failed to update settings: ' + (err.message || 'Server error'), true);
        this.cdr.detectChanges();
      }
    });
  }

  triggerGC(): void {
    this.isGCRunning = true;
    this.processingSettingsService.triggerGC().subscribe({
      next: (result) => {
        this.isGCRunning = false;
        this.showSnackbar(`GC completed. Freed ${result.freedMB} MB. Current usage: ${result.currentUsagePercent.toFixed(1)}%`);
        this.loadProcessingSettings(); // Refresh to show updated memory
      },
      error: (err) => {
        this.isGCRunning = false;
        this.showSnackbar('GC request failed', true);
      }
    });
  }

  getMemoryBarColor(): string {
    if (!this.processingSettings) return 'primary';
    const status = this.processingSettings.memoryStatus.status;
    if (status === 'CRITICAL') return 'warn';
    if (status === 'WARNING') return 'accent';
    return 'primary';
  }

  private initializeWebSocket(): void {
    // Subscribe to connection state
    this.connectionSubscription = this.webSocketService.connectionState$.subscribe(state => {
      const previousState = this.wsConnectionState;
      this.wsConnectionState = state;
      console.log('WebSocket connection state:', state);

      // Reload active tasks when WebSocket connects/reconnects
      if (state === WebSocketConnectionState.CONNECTED && previousState !== WebSocketConnectionState.CONNECTED) {
        console.log('WebSocket connected - reloading active ingest tasks');
        this.loadActiveIngestTasks();
      }

      this.cdr.detectChanges();
    });

    // Connect to WebSocket
    this.webSocketService.connect();

    // Subscribe to all ingest progress updates
    this.wsSubscription = this.webSocketService.subscribeToAllTasks().subscribe(update => {
      this.handleProgressUpdate(update);
    });

    // Subscribe to subprocess logs for real-time log display
    this.logsSubscription = this.webSocketService.subscribeToAllLogs().subscribe(logEntry => {
      this.handleLogEntry(logEntry);
    });
  }

  private loadActiveIngestTasks(): void {
    console.log('Loading active ingest tasks from backend...');
    this.documentService.getAllIngestTasks().subscribe({
      next: (tasks) => {
        console.log(`Loaded ${tasks.length} active ingest tasks`);
        // Only add tasks that are still in progress or recently completed
        tasks.forEach(task => {
          // Add all tasks - let handleProgressUpdate logic handle cleanup
          this.activeUploads.set(task.taskId, task);

          // Schedule removal for completed/failed tasks (same as handleProgressUpdate)
          if (task.status === IngestStatus.COMPLETED || task.status === IngestStatus.FAILED) {
            setTimeout(() => {
              this.activeUploads.delete(task.taskId);
              this.activeUploadsArray = Array.from(this.activeUploads.values());
              this.cdr.detectChanges();
            }, 10000);
          }
        });
        this.activeUploadsArray = Array.from(this.activeUploads.values());
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.warn('Could not load active ingest tasks:', err);
      }
    });
  }

  /**
   * Poll for active tasks as a fallback mechanism.
   * This ensures we catch updates even if WebSocket messages are missed.
   * Only polls when there are active uploads or WebSocket is disconnected.
   */
  private startTaskPolling(): void {
    // Poll every 2 seconds when there are active tasks or WS is not connected
    this.taskPollingSubscription = interval(2000).subscribe(() => {
      const hasActiveUploads = this.hasActiveProcessing();
      const wsNotConnected = this.wsConnectionState !== WebSocketConnectionState.CONNECTED;

      // Only poll if we have active tasks OR WebSocket isn't working
      if (hasActiveUploads || wsNotConnected) {
        this.documentService.getAllIngestTasks().subscribe({
          next: (tasks) => {
            let hasChanges = false;
            tasks.forEach(task => {
              // Skip updates for locally-cancelled tasks to prevent overwriting
              if (this.cancelledTaskIds.has(task.taskId)) {
                return;
              }
              const existing = this.activeUploads.get(task.taskId);
              // Update if task is new or has changed
              if (!existing || existing.progressPercent !== task.progressPercent ||
                existing.phase !== task.phase || existing.status !== task.status) {
                this.activeUploads.set(task.taskId, task);
                hasChanges = true;

                // Schedule removal for completed/failed/cancelled tasks
                if (task.status === IngestStatus.COMPLETED || task.status === IngestStatus.FAILED || task.status === IngestStatus.CANCELLED) {
                  setTimeout(() => {
                    this.activeUploads.delete(task.taskId);
                    this.cancelledTaskIds.delete(task.taskId);
                    this.activeUploadsArray = Array.from(this.activeUploads.values()).map(u => ({ ...u }));
                    this.cdr.detectChanges();
                  }, 10000);

                  // Refresh file list on completion
                  if (task.status === IngestStatus.COMPLETED && (!existing || existing.status !== IngestStatus.COMPLETED)) {
                    this.refreshAllData();
                    this.showSnackbar(`File '${task.fileName}' processed successfully!`, false);
                  }
                }
              }
            });

            // Remove tasks that are no longer in the backend list (cleaned up)
            const backendTaskIds = new Set(tasks.map(t => t.taskId));
            this.activeUploads.forEach((_, taskId) => {
              if (!backendTaskIds.has(taskId)) {
                this.activeUploads.delete(taskId);
                this.cancelledTaskIds.delete(taskId);
                hasChanges = true;
              }
            });

            if (hasChanges) {
              this.cdr.detectChanges();
            }
          },
          error: (err) => {
            // Silently ignore polling errors to avoid spam
          }
        });
      }
    });
  }

  /**
   * Check warmup status and poll until ready.
   */
  private checkWarmupStatus(): void {
    this.documentService.getWarmupStatus().subscribe({
      next: (status: WarmupStatus) => {
        this.warmupStatus = status;
        this.isWarmingUp = !status.ready;

        // If still warming up, poll every 2 seconds
        if (this.isWarmingUp) {
          this.warmupPollingSubscription = interval(2000).subscribe(() => {
            this.documentService.getWarmupStatus().subscribe({
              next: (s: WarmupStatus) => {
                this.warmupStatus = s;
                this.isWarmingUp = !s.ready;
                this.cdr.detectChanges();

                // Stop polling once ready
                if (s.ready && this.warmupPollingSubscription) {
                  this.warmupPollingSubscription.unsubscribe();
                  this.warmupPollingSubscription = null;
                }
              }
            });
          });
        }
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.warn('Could not check warmup status:', err);
        // Assume ready if we can't check
        this.isWarmingUp = false;
      }
    });
  }

  private handleProgressUpdate(update: IngestProgressUpdate): void {
    console.log('Progress update received:', update, 'phase:', update.phase);
    console.log('=== WORKER STATUS DEBUG ===');
    console.log('stats present:', !!update.stats);
    if (update.stats) {
      console.log('workerStatuses:', update.stats.workerStatuses);
      console.log('workerStatuses length:', update.stats.workerStatuses?.length);
      console.log('queueStatus:', update.stats.queueStatus);
      // Debug embedding batch metrics
      if (update.stats.currentEmbeddingBatch) {
        const batch = update.stats.currentEmbeddingBatch;
        console.log('=== EMBEDDING BATCH METRICS ===');
        console.log('batchNumber:', batch.batchNumber, 'inputTexts:', batch.inputTexts);
        console.log('currentStep:', batch.currentStep, 'heartbeatSeconds:', batch.heartbeatSeconds);
        console.log('forwardPassTimeMs:', batch.forwardPassTimeMs, 'isStuck:', batch.isStuck);
        // Auto-expand batch details when heartbeat data is present
        if (batch.heartbeatSeconds && batch.heartbeatSeconds > 0) {
          this.expandedBatchDetails.add(update.taskId);
        }
      }
    }
    // Skip non-cancelled updates for locally-cancelled tasks to prevent overwriting
    if (this.cancelledTaskIds.has(update.taskId) && update.status !== IngestStatus.CANCELLED) {
      return;
    }

    // Fetch environment when we first see a task (captured at job queue time)
    if (!this.taskEnvironments.has(update.taskId)) {
      this.fetchTaskEnvironment(update.taskId);
    }

    // Merge with existing state to preserve stats that may not be in every update
    // This is especially important for subprocess mode which may send partial updates
    const existing = this.activeUploads.get(update.taskId);
    const merged = this.mergeProgressUpdate(existing, update);
    this.activeUploads.set(update.taskId, merged);
    // Create a new array with new object references for reliable change detection
    this.activeUploadsArray = Array.from(this.activeUploads.values()).map(u => ({ ...u }));

    // Remove completed/failed/cancelled tasks after a delay
    if (update.status === IngestStatus.COMPLETED ||
      update.status === IngestStatus.FAILED ||
      update.status === IngestStatus.CANCELLED) {
      setTimeout(() => {
        this.activeUploads.delete(update.taskId);
        this.cancelledTaskIds.delete(update.taskId);
        this.activeUploadsArray = Array.from(this.activeUploads.values());
        this.cdr.detectChanges();
      }, 10000); // Keep for 10 seconds

      // Refresh file list on completion
      if (update.status === IngestStatus.COMPLETED) {
        this.refreshAllData();
        this.showSnackbar(`File '${update.fileName}' processed successfully!`, false);
      } else if (update.status === IngestStatus.FAILED) {
        this.showSnackbar(`Failed to process '${update.fileName}': ${update.errorMessage}`, true);
      } else if (update.status === IngestStatus.CANCELLED) {
        this.showSnackbar(`'${update.fileName}' was cancelled`, false);
      }
    }

    this.cdr.detectChanges();
  }

  /**
   * Handle log entries from subprocess mode.
   * Stores logs per task for display in the UI.
   */
  private handleLogEntry(logEntry: IngestLogEntry): void {
    if (!logEntry || !logEntry.taskId) return;

    // Initialize log array for this task if not exists
    if (!this.taskLogs.has(logEntry.taskId)) {
      this.taskLogs.set(logEntry.taskId, []);
    }

    // Add log entry (keep last 200 logs per task to prevent memory issues)
    const logs = this.taskLogs.get(logEntry.taskId)!;
    logs.push(logEntry);
    if (logs.length > 200) {
      logs.shift(); // Remove oldest entry
    }

    console.log(`[Task ${logEntry.taskId}] Log received: [${logEntry.level}] ${logEntry.message}`);
    this.cdr.detectChanges();
  }

  /**
   * Merge a new progress update with existing state.
   * This preserves stats fields that may not be present in every update,
   * which is especially important for subprocess mode that may send partial updates.
   */
  private mergeProgressUpdate(existing: IngestProgressUpdate | undefined, update: IngestProgressUpdate): IngestProgressUpdate {
    if (!existing) {
      return { ...update };
    }

    // Deep merge stats - prefer new values, fallback to existing
    let mergedStats = update.stats;
    if (existing.stats && update.stats) {
      mergedStats = {
        ...existing.stats,
        ...update.stats,
        // Preserve specific numeric fields only if not present in update
        documentsLoaded: update.stats.documentsLoaded ?? existing.stats.documentsLoaded,
        chunksCreated: update.stats.chunksCreated ?? existing.stats.chunksCreated,
        chunksEmbedded: update.stats.chunksEmbedded ?? existing.stats.chunksEmbedded,
        chunksIndexed: update.stats.chunksIndexed ?? existing.stats.chunksIndexed,
        documentsIndexed: update.stats.documentsIndexed ?? existing.stats.documentsIndexed,
        // Preserve nested objects if not present in update
        queueStatus: update.stats.queueStatus || existing.stats.queueStatus,
        currentEmbeddingBatch: update.stats.currentEmbeddingBatch || existing.stats.currentEmbeddingBatch,
        subprocessRuntimeInfo: update.stats.subprocessRuntimeInfo || existing.stats.subprocessRuntimeInfo,
        workerStatuses: update.stats.workerStatuses || existing.stats.workerStatuses,
      };
    } else if (existing.stats && !update.stats) {
      // Keep existing stats if update has none
      mergedStats = existing.stats;
    }

    return {
      ...existing,
      ...update,
      stats: mergedStats,
    };
  }

  /**
   * Get logs for a specific task.
   */
  getTaskLogs(taskId: string): IngestLogEntry[] {
    return this.taskLogs.get(taskId) || [];
  }

  getActiveUploadsArray(): IngestProgressUpdate[] {
    return Array.from(this.activeUploads.values());
  }

  trackByTaskId(index: number, upload: IngestProgressUpdate): string {
    return upload.taskId;
  }

  getProgressColor(phase: IngestPhase): string {
    switch (phase) {
      case IngestPhase.COMPLETED: return 'primary';
      case IngestPhase.FAILED: return 'warn';
      default: return 'accent';
    }
  }

  getPhaseIcon(phase: IngestPhase): string {
    switch (phase) {
      case IngestPhase.QUEUED: return 'hourglass_empty';
      case IngestPhase.UPLOADING: return 'cloud_upload';
      case IngestPhase.LOADING: return 'description';
      case IngestPhase.CONVERTING: return 'text_fields';
      case IngestPhase.CHUNKING: return 'content_cut';
      case IngestPhase.EMBEDDING: return 'memory';
      case IngestPhase.INDEXING: return 'storage';
      case IngestPhase.COMPLETED: return 'check_circle';
      case IngestPhase.FAILED: return 'error';
      default: return 'pending';
    }
  }

  hasActiveProcessing(): boolean {
    return this.getActiveUploadsArray().some(u => u.status === IngestStatus.IN_PROGRESS);
  }

  /**
   * Cancel an active ingest task.
   * Calls the backend directly - the backend is the source of truth for task state.
   */
  cancelIngestTask(taskId: string): void {
    // Get local task info if available (for UI feedback), but don't require it
    const localTask = this.activeUploads.get(taskId);

    // Don't allow cancelling if we know locally it's already terminal
    if (localTask && (localTask.status === IngestStatus.COMPLETED ||
      localTask.status === IngestStatus.FAILED ||
      localTask.status === IngestStatus.CANCELLED)) {
      this.showSnackbar(`Task already ${localTask.status.toLowerCase()}`, true);
      return;
    }

    // Always try to cancel on the backend - it's the source of truth
    this.documentService.cancelIngestTask(taskId).subscribe({
      next: (response) => {
        const fileName = localTask?.fileName || `task ${taskId.substring(0, 8)}...`;
        if (response.cancelled) {
          this.showSnackbar(`Cancelling '${fileName}'...`, false);
          // Track this task as locally cancelled to prevent polling from overwriting
          this.cancelledTaskIds.add(taskId);
          // Update the local task status immediately to show UI feedback
          if (localTask) {
            const updatedTask: IngestProgressUpdate = {
              ...localTask,
              status: IngestStatus.CANCELLED,
              message: 'Cancellation requested...',
              currentStep: 'Stopping'
            };
            this.activeUploads.set(taskId, updatedTask);
            this.activeUploadsArray = Array.from(this.activeUploads.values()).map(u => ({ ...u }));
            this.cdr.detectChanges();
          }

          // Remove from UI after a delay
          setTimeout(() => {
            this.activeUploads.delete(taskId);
            this.cancelledTaskIds.delete(taskId);
            this.activeUploadsArray = Array.from(this.activeUploads.values()).map(u => ({ ...u }));
            this.cdr.detectChanges();
          }, 5000);
        } else {
          this.showSnackbar(response.message || 'Could not cancel task', true);
        }
      },
      error: (err) => {
        console.error('Failed to cancel task:', err);
        // Check if it's a 404 - task not found on backend
        if (err.status === 404) {
          this.showSnackbar('Task not found on server - it may have already completed', true);
          // Remove from local UI since backend doesn't have it
          this.activeUploads.delete(taskId);
          this.cancelledTaskIds.delete(taskId);
          this.activeUploadsArray = Array.from(this.activeUploads.values()).map(u => ({ ...u }));
          this.cdr.detectChanges();
        } else {
          this.showSnackbar('Failed to cancel task: ' + (err.error?.message || err.message || 'Server error'), true);
        }
      }
    });
  }

  /**
   * Check if a task can be cancelled (is in a cancellable state).
   */
  canCancelTask(upload: IngestProgressUpdate): boolean {
    return upload.status === IngestStatus.IN_PROGRESS ||
      upload.status === IngestStatus.PENDING;
  }

  isPhaseActive(upload: IngestProgressUpdate, phase: string): boolean {
    // Cast to string to handle enum vs string comparison
    const currentPhase = String(upload.phase);
    return currentPhase === phase;
  }

  isPhaseComplete(upload: IngestProgressUpdate, phase: string): boolean {
    const phaseOrder = ['QUEUED', 'LOADING', 'CONVERTING', 'CHUNKING', 'EMBEDDING', 'INDEXING', 'COMPLETED'];
    // Cast to string to handle enum vs string comparison
    const currentPhase = String(upload.phase);
    const currentIndex = phaseOrder.indexOf(currentPhase);
    const checkIndex = phaseOrder.indexOf(phase);
    return currentIndex > checkIndex || currentPhase === 'COMPLETED';
  }

  formatTime(ms: number): string {
    if (ms < 1000) {
      return `${ms}ms`;
    } else if (ms < 60000) {
      return `${(ms / 1000).toFixed(1)}s`;
    } else {
      const minutes = Math.floor(ms / 60000);
      const seconds = Math.floor((ms % 60000) / 1000);
      return `${minutes}m ${seconds}s`;
    }
  }

  formatBytes(bytes: number | undefined | null): string {
    if (bytes === null || bytes === undefined) return 'N/A';
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  calculateEmbeddingPercent(upload: IngestProgressUpdate): number {
    if (!upload.stats || upload.stats.chunksCreated === 0) return 0;
    return Math.round((upload.stats.chunksEmbedded / upload.stats.chunksCreated) * 100);
  }

  calculateIndexingPercent(upload: IngestProgressUpdate): number {
    if (!upload.stats || upload.stats.documentsLoaded === 0) return 0;
    return Math.round((upload.stats.documentsIndexed / upload.stats.documentsLoaded) * 100);
  }

  calculateBatchProgress(upload: IngestProgressUpdate): number {
    if (!upload.stats || !upload.stats.totalBatches || upload.stats.totalBatches === 0) return 0;
    const current = upload.stats.currentBatch || 0;
    return Math.round((current / upload.stats.totalBatches) * 100);
  }

  /**
   * Calculate progress percentage for a specific stage.
   */
  calculateStagePercent(completed: number | undefined, total: number | undefined): number {
    if (!total || total === 0) return 0;
    const c = completed || 0;
    return Math.min(100, Math.round((c / total) * 100));
  }

  /**
   * Detect passthrough mode: embedding is done by indexer if chunksEmbedded stays 0
   * while chunksIndexed is progressing.
   */
  isPassthroughMode(upload: IngestProgressUpdate): boolean {
    if (!upload.stats) return false;
    const embedded = upload.stats.chunksEmbedded || 0;
    const indexed = upload.stats.chunksIndexed || 0;
    // If we have indexed chunks but no embedded chunks, indexer is handling embedding
    return embedded === 0 && indexed > 0;
  }

  /**
   * Calculate estimated time remaining based on throughput.
   */
  calculateETA(upload: IngestProgressUpdate): string | null {
    if (!upload.stats || !upload.stats.chunksPerSecond || upload.stats.chunksPerSecond <= 0) {
      return null;
    }

    const chunksCreated = upload.stats.chunksCreated || 0;
    const chunksIndexed = upload.stats.chunksIndexed || upload.stats.documentsIndexed || 0;
    const remaining = chunksCreated - chunksIndexed;

    if (remaining <= 0) return null;

    const secondsRemaining = Math.ceil(remaining / upload.stats.chunksPerSecond);

    if (secondsRemaining < 60) {
      return `${secondsRemaining}s`;
    } else if (secondsRemaining < 3600) {
      const mins = Math.floor(secondsRemaining / 60);
      const secs = secondsRemaining % 60;
      return `${mins}m ${secs}s`;
    } else {
      const hours = Math.floor(secondsRemaining / 3600);
      const mins = Math.floor((secondsRemaining % 3600) / 60);
      return `${hours}h ${mins}m`;
    }
  }

  // Worker status helpers
  getWorkersByType(workers: any[], type: string): any[] {
    return workers.filter(w => w.workerType === type);
  }

  getTotalProcessed(workers: any[], type: string): number {
    return workers
      .filter(w => w.workerType === type)
      .reduce((sum, w) => sum + (w.itemsProcessed || 0), 0);
  }

  // TrackBy function for worker ngFor to improve performance
  trackByWorkerId(index: number, worker: any): string {
    return `${worker.workerType}-${worker.workerId}`;
  }

  // Calculate embedding progress percentage
  getEmbeddingProgress(upload: IngestProgressUpdate): number {
    if (!upload.stats || !upload.stats.chunksCreated || upload.stats.chunksCreated === 0) {
      return 0;
    }
    const embedded = upload.stats.chunksEmbedded || 0;
    return Math.round((embedded / upload.stats.chunksCreated) * 100);
  }

  // Calculate indexing progress percentage
  getIndexingProgress(upload: IngestProgressUpdate): number {
    if (!upload.stats || !upload.stats.chunksCreated || upload.stats.chunksCreated === 0) {
      return 0;
    }
    const indexed = upload.stats.documentsIndexed || 0;
    return Math.round((indexed / upload.stats.chunksCreated) * 100);
  }

  togglePipelineExpanded(taskId: string): void {
    if (this.expandedPipelines.has(taskId)) {
      this.expandedPipelines.delete(taskId);
    } else {
      this.expandedPipelines.add(taskId);
    }
    this.cdr.markForCheck();
  }

  isPipelineExpanded(taskId: string): boolean {
    return this.expandedPipelines.has(taskId);
  }

  toggleBatchDetails(taskId: string): void {
    if (this.expandedBatchDetails.has(taskId)) {
      this.expandedBatchDetails.delete(taskId);
    } else {
      this.expandedBatchDetails.add(taskId);
    }
    this.cdr.markForCheck();
  }

  isBatchDetailsExpanded(taskId: string): boolean {
    return this.expandedBatchDetails.has(taskId);
  }

  toggleSubprocessDetails(taskId: string): void {
    if (this.expandedSubprocessDetails.has(taskId)) {
      this.expandedSubprocessDetails.delete(taskId);
    } else {
      this.expandedSubprocessDetails.add(taskId);
    }
    this.cdr.markForCheck();
  }

  isSubprocessDetailsExpanded(taskId: string): boolean {
    return this.expandedSubprocessDetails.has(taskId);
  }

  toggleSubprocessLogs(taskId: string): void {
    if (this.expandedSubprocessLogs.has(taskId)) {
      this.expandedSubprocessLogs.delete(taskId);
    } else {
      this.expandedSubprocessLogs.add(taskId);
    }
    this.cdr.markForCheck();
  }

  isSubprocessLogsExpanded(taskId: string): boolean {
    return this.expandedSubprocessLogs.has(taskId);
  }

  formatDuration(ms: number | undefined | null): string {
    if (ms === null || ms === undefined) return 'N/A';
    if (ms < 1000) return ms + 'ms';
    const seconds = Math.floor(ms / 1000);
    if (seconds < 60) return seconds + 's';
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    if (minutes < 60) return minutes + 'm ' + remainingSeconds + 's';
    const hours = Math.floor(minutes / 60);
    const remainingMinutes = minutes % 60;
    return hours + 'h ' + remainingMinutes + 'm';
  }

  ngAfterViewInit(): void {
    this.assignPaginatorsAndSorters();
  }

  private assignPaginatorsAndSorters(): void {
    if (this.configuredSourcesPaginator && this.configuredSourcesDataSource.paginator !== this.configuredSourcesPaginator) {
      this.configuredSourcesDataSource.paginator = this.configuredSourcesPaginator;
    }
    if (this.configuredSourcesSort && this.configuredSourcesDataSource.sort !== this.configuredSourcesSort) {
      this.configuredSourcesDataSource.sort = this.configuredSourcesSort;
    }
    if (this.uploadedFilesPaginator && this.uploadedFilesDataSource.paginator !== this.uploadedFilesPaginator) {
      this.uploadedFilesDataSource.paginator = this.uploadedFilesPaginator;
    }
    if (this.uploadedFilesSort && this.uploadedFilesDataSource.sort !== this.uploadedFilesSort) {
      this.uploadedFilesDataSource.sort = this.uploadedFilesSort;
    }
  }

  private updateTemplateFlags(): void {
    const baseLoading = this.isLoading || this.isLoadingDebuggerStatus || this.isBatchProcessing || this.isAnalyzingFile || this.isTestingUpload;

    this.showLoadersSpinner = baseLoading && (!this.availableLoaders || this.availableLoaders.length === 0);
    this.showNoLoadersMessage = !baseLoading && (!this.availableLoaders || this.availableLoaders.length === 0);
    this.showLoadersList = !!(this.availableLoaders && this.availableLoaders.length > 0);

    this.showChunkersSpinner = baseLoading && (!this.availableChunkers || this.availableChunkers.length === 0);
    this.showNoChunkersMessage = !baseLoading && (!this.availableChunkers || this.availableChunkers.length === 0);
    this.showChunkersList = !!(this.availableChunkers && this.availableChunkers.length > 0);

    this.showConfiguredSourcesSpinner = baseLoading && this.configuredSourcesDataSource.data.length === 0;
    this.showNoConfiguredSourcesMessage = !baseLoading && this.configuredSourcesDataSource.data.length === 0;
    this.showConfiguredSourcesTable = this.configuredSourcesDataSource.data.length > 0;

    this.showUploadedFilesSpinner = baseLoading && this.uploadedFilesDataSource.data.length === 0;
    this.showNoUploadedFilesMessage = !baseLoading && this.uploadedFilesDataSource.data.length === 0;
    this.showUploadedFilesTable = this.uploadedFilesDataSource.data.length > 0;

    this.showBatchResults = !!(this.batchResults && !this.isBatchProcessing && (this.batchResultPaths?.length || 0) > 0);
    this.showDebugStatusCard = !!this.debuggerStatus && !this.isLoadingDebuggerStatus;
    this.showDebugAnalysisCard = !!this.debugAnalysisResult && !this.isAnalyzingFile;
    this.cdr.detectChanges();
  }

  refreshAllData(callback?: () => void): void {
    if (this.isLoading && !callback) { // Prevent multiple parallel refreshes unless a callback is provided
      return;
    }
    this.isLoading = true;
    this.updateTemplateFlags();

    const loaders$: Observable<LoaderInfo[]> = this.documentService.getAvailableLoaders().pipe(catchError(err => {
      this.handleLoadError('available loaders', err);
      return of([] as LoaderInfo[]);
    }));
    const chunkers$: Observable<ChunkerInfo[]> = this.documentService.getAvailableChunkers().pipe(catchError(err => {
      this.handleLoadError('available chunkers', err);
      return of([] as ChunkerInfo[]);
    }));
    const uploadedFiles$: Observable<UploadedFilesData> = this.documentService.getUploadedFiles().pipe(catchError(err => {
      this.handleLoadError('uploaded files', err);
      return of({ files: [], uploaded_files_location: 'Error loading path' } as UploadedFilesData);
    }));
    const configuredSources$: Observable<string[]> = this.documentService.getConfiguredSources().pipe(catchError(err => {
      this.handleLoadError('configured sources', err);
      return of([] as string[]);
    }));

    forkJoin([loaders$, chunkers$, uploadedFiles$, configuredSources$]).pipe(
      finalize(() => {
        this.isLoading = false;
        this.updateTemplateFlags();
        if (callback) {
          callback();
        }
      })
    ).subscribe({
      next: ([loaders, chunkers, uploadedFilesData, sources]: [LoaderInfo[], ChunkerInfo[], UploadedFilesData, string[]]) => {
        this.availableLoaders = loaders || [];
        this.availableChunkers = chunkers || [];
        this._uploadedFiles = uploadedFilesData?.files || [];
        this.uploadedFilesLocation = uploadedFilesData?.uploaded_files_location || 'N/A';
        this.updateUploadedFilesTable();
        this._configuredSources = sources || [];
        this.updateConfiguredSourcesTable();
      },
      error: (err) => { // This error block might be redundant due to individual catchErrors, but good for a global fallback
        this.showSnackbar(`An error occurred while loading initial data: ${err.message || 'Server error'}`, true);
      }
    });
  }

  private handleLoadError(dataType: string, error: HttpErrorResponse) {
    this.showSnackbar(`Error loading ${dataType}: ${error.message || 'Server error'}`, true, 6000);
    if (dataType === 'available loaders') this.availableLoaders = [];
    if (dataType === 'available chunkers') this.availableChunkers = [];
    if (dataType === 'uploaded files') {
      this._uploadedFiles = []; this.uploadedFilesLocation = 'Error loading path'; this.updateUploadedFilesTable();
    }
    if (dataType === 'configured sources') {
      this._configuredSources = []; this.updateConfiguredSourcesTable();
    }
  }

  getSimpleName(className: any): string {
    const nameStr = this.safeToString(className);
    const parts = nameStr.split('.');
    return parts.pop() || nameStr; // Return full string if no dots
  }

  get hasBatchSourceContent(): boolean {
    return this.batchSourcePaths.trim().length > 0;
  }

  get isProcessBatchDisabled(): boolean {
    return this.isBatchProcessing || !this.hasBatchSourceContent || !this.batchSelectedLoader;
  }

  hasMetadata(summary: DocumentSummary | undefined | null): boolean {
    return this.hasValidMetadata(summary);
  }

  hasValidMetadata(summary: DocumentSummary | undefined | null): boolean {
    if (!summary || !summary.metadata) return false;
    try {
      JSON.stringify(summary.metadata); // Test for circular refs
      return Object.keys(summary.metadata).length > 0;
    } catch (error) {
      console.warn('Metadata contains circular references or is invalid:', summary.metadata, error);
      return false; // Treat as invalid
    }
  }

  getSafeMetadata(summary: DocumentSummary | undefined | null): any {
    if (!this.hasValidMetadata(summary)) {
      return {}; // Return empty or some placeholder if not valid
    }
    try {
      return JSON.parse(JSON.stringify(summary!.metadata)); // Deep copy
    } catch (error) {
      console.warn('Error creating safe metadata copy:', summary!.metadata, error);
      return { "error": "Could not display metadata due to parsing issues." };
    }
  }

  getBatchResultCount(path: string): number {
    return this.batchResults?.[path]?.count || 0;
  }

  getBatchResultSummaries(path: string): DocumentSummary[] {
    return this.batchResults?.[path]?.summaries || [];
  }

  getBatchResultError(path: string): string | undefined {
    return this.batchResults?.[path]?.error;
  }

  safeToString(value: any): string {
    if (value === null || value === undefined) {
      return '';
    }
    if (typeof value === 'string') {
      return value;
    }
    try {
      return String(value);
    } catch (e) {
      return '';
    }
  }

  safeLength(value: any): number {
    return this.safeToString(value).length;
  }

  // TrackBy functions for ngFor performance optimization (NEW)
  trackByLoaderName(index: number, loader: LoaderInfo): string {
    return loader.name || index.toString();
  }

  trackByChunkerName(index: number, chunker: ChunkerInfo): string {
    return chunker.name || index.toString();
  }

  updateConfiguredSourcesTable(): void {
    const tableData: ConfiguredSourceElement[] = (this._configuredSources || [])
      .filter(s => s && !s.toLowerCase().includes("no primary document sources configured"))
      .map((s, index) => ({ id: index + 1, path: this.safeToString(s), type: 'Property' }));

    if (this.uploadedFilesLocation && this.uploadedFilesLocation !== 'N/A' && !this.uploadedFilesLocation.includes("error_uploads_path_not_configured") && !this.uploadedFilesLocation.includes("Error loading path")) {
      if (!tableData.some(item => item.path === this.uploadedFilesLocation && item.type === 'Upload Path')) {
        tableData.push({
          id: tableData.length + 1,
          path: this.safeToString(this.uploadedFilesLocation),
          type: 'Upload Path'
        });
      }
    }
    this.configuredSourcesDataSource.data = tableData;
    this.assignPaginatorsAndSorters(); // Re-assign after data change
    this.updateTemplateFlags();
  }

  updateUploadedFilesTable(): void {
    this.uploadedFilesDataSource.data = (this._uploadedFiles || []).map((f, index) => ({
      id: index + 1,
      name: f.fileName || this.safeToString(f),
    }));
    this.assignPaginatorsAndSorters(); // Re-assign after data change
    this.updateTemplateFlags();
  }

  applyFilterConfigured(event: Event) {
    const filterValue = (event.target as HTMLInputElement).value;
    this.configuredSourcesDataSource.filter = filterValue.trim().toLowerCase();
    if (this.configuredSourcesDataSource.paginator) this.configuredSourcesDataSource.paginator.firstPage();
  }

  applyFilterUploaded(event: Event) {
    const filterValue = (event.target as HTMLInputElement).value;
    this.uploadedFilesDataSource.filter = filterValue.trim().toLowerCase();
    if (this.uploadedFilesDataSource.paginator) this.uploadedFilesDataSource.paginator.firstPage();
  }

  openAddSourceDialog(): void {
    const dialogRef = this.dialog.open(AddSourceDialogComponent, {
      width: '600px',
      data: {
        availableLoaders: this.availableLoaders,
        availableChunkers: this.availableChunkers
      },
      disableClose: true
    });
    dialogRef.afterClosed().subscribe((result: AddSourceDialogResult | undefined) => {
      if (result) this.submitDocumentSource(result);
    });
  }

  private submitDocumentSource(data: AddSourceDialogResult): void {
    // DEBUG: Log what we received from the dialog
    console.log('=== DOCUMENT MANAGER - submitDocumentSource DEBUG ===');
    console.log('data.chunkerName:', data.chunkerName);
    console.log('data.selectedLoader:', data.selectedLoader);
    console.log('data.files:', data.files?.map(f => f.name));
    console.log('Full data:', JSON.stringify({
      chunkerName: data.chunkerName,
      selectedLoader: data.selectedLoader,
      rebuildIndex: data.rebuildIndex,
      chunkerOptions: data.chunkerOptions,
      filesCount: data.files?.length
    }, null, 2));
    console.log('=== END DEBUG ===');

    this.isLoading = true; this.updateTemplateFlags();
    const handleSuccess = (response: FileUploadResponse | SimpleMessageResponse, operation: string) => {
      this.showSnackbar(response.message || `${operation} successful!`);
      this.refreshAllData(() => { // Refresh all data after success
        if (data?.rebuildIndex) {
          this.onRebuildIndex(); // isLoading will be handled by onRebuildIndex
        } else {
          this.isLoading = false;
          this.updateTemplateFlags();
        }
      });
    };
    const handleError = (operation: string, err: HttpErrorResponse) => {
      this.handleOperationError(operation, err); this.isLoading = false; this.updateTemplateFlags();
    };

    // Check if we have multiple files for batch upload
    if (data.files && data.files.length > 0) {
      // Use async batch upload for multiple files
      if (this.useAsyncUpload) {
        this.documentService.uploadFilesAsync(data.files, data.selectedLoader, data.chunkerName, data.processingMode).subscribe({
          next: (response: BatchAsyncUploadResponse) => {
            const accepted = response.acceptedCount;
            const rejected = response.rejectedCount;
            if (accepted > 0) {
              this.showSnackbar(`${accepted} file(s) queued for processing. ${rejected > 0 ? rejected + ' rejected.' : ''} Track progress in Active Uploads section.`);
            } else {
              this.showSnackbar(`All files rejected: ${response.message}`, true);
            }
            this.isLoading = false;
            this.updateTemplateFlags();
          },
          error: (e) => handleError('Batch async file upload', e)
        });
      } else {
        // Fall back to uploading files sequentially (sync mode)
        let completedCount = 0;
        let failedCount = 0;
        const totalFiles = data.files.length;

        data.files.forEach((file, index) => {
          this.documentService.uploadFile(file, data.selectedLoader).subscribe({
            next: (r) => {
              completedCount++;
              if (completedCount + failedCount === totalFiles) {
                this.showSnackbar(`Batch upload complete: ${completedCount} successful, ${failedCount} failed.`);
                this.refreshAllData(() => {
                  if (data?.rebuildIndex) {
                    this.onRebuildIndex();
                  } else {
                    this.isLoading = false;
                    this.updateTemplateFlags();
                  }
                });
              }
            },
            error: (e) => {
              failedCount++;
              console.error(`Failed to upload ${file.name}:`, e);
              if (completedCount + failedCount === totalFiles) {
                this.showSnackbar(`Batch upload complete: ${completedCount} successful, ${failedCount} failed.`, failedCount > 0);
                this.isLoading = false;
                this.updateTemplateFlags();
              }
            }
          });
        });
      }
    } else if (data.file) {
      // Single file upload (backwards compatibility)
      if (this.useAsyncUpload) {
        this.documentService.uploadFileAsync(data.file, data.selectedLoader, data.chunkerName, data.processingMode).subscribe({
          next: (response: AsyncUploadResponse) => {
            if (response.accepted && response.taskId) {
              this.showSnackbar(`File '${response.fileName}' queued for processing. Track progress in Active Uploads section.`);
              // WebSocket will handle progress updates
              this.isLoading = false;
              this.updateTemplateFlags();
            } else {
              this.showSnackbar(`Upload rejected: ${response.error}`, true);
              this.isLoading = false;
              this.updateTemplateFlags();
            }
          },
          error: (e) => handleError('Async file upload', e)
        });
      } else {
        // Fall back to synchronous upload
        this.documentService.uploadFile(data.file, data.selectedLoader).subscribe({
          next: (r) => handleSuccess(r, 'File upload'), error: (e) => handleError('File upload', e)
        });
      }
    } else if (data.url) {
      const request: AddUrlRequest = { url: data.url, fileName: data.fileName, loader: data.selectedLoader };
      this.documentService.addUrl(request).subscribe({
        next: (r) => handleSuccess(r, 'Add URL'), error: (e) => handleError('Add URL', e)
      });
    } else {
      this.isLoading = false; this.updateTemplateFlags(); // No action taken
    }
  }

  toggleAsyncUpload(): void {
    this.useAsyncUpload = !this.useAsyncUpload;
    this.showSnackbar(`Async upload ${this.useAsyncUpload ? 'enabled' : 'disabled'}. Large files will ${this.useAsyncUpload ? 'be processed in background' : 'block the UI'}.`);
  }

  onProcessBatch(): void {
    if (!this.hasBatchSourceContent) { this.showSnackbar('Please enter source paths/URLs for batch processing.', true); return; }
    if (!this.batchSelectedLoader) { this.showSnackbar('Please select a loader for batch processing.', true); return; }
    this.isBatchProcessing = true; this.batchResults = null; this.batchResultPaths = []; this.updateTemplateFlags();
    const items: BatchLoadRequestItem[] = this.batchSourcePaths.split(',')
      .map(p => p.trim()).filter(p => p)
      .map(pathOrUrl => ({
        pathOrUrl,
        type: pathOrUrl.toLowerCase().startsWith('http://') || pathOrUrl.toLowerCase().startsWith('https://') ? DocumentSourceType.URL : DocumentSourceType.FILE,
        loaderName: this.batchSelectedLoader
      }));

    if (items.length === 0) {
      this.showSnackbar("No valid source paths or URLs provided for batch processing.", true);
      this.isBatchProcessing = false; this.updateTemplateFlags(); return;
    }

    this.documentService.processBatch({ items, defaultLoaderName: this.batchSelectedLoader }).pipe(
      finalize(() => { this.isBatchProcessing = false; this.updateTemplateFlags(); })
    ).subscribe({
      next: (res) => {
        this.batchResults = res.details || null;
        this.batchResultPaths = this.batchResults ? Object.keys(this.batchResults) : [];
        this.showSnackbar(`${res.message || 'Batch process complete.'} Successful: ${res.successful_items}, Failed: ${res.failed_items}.`);
        this.batchSourcePaths = ''; // Clear input after processing
        this.refreshAllData(); // Refresh data to reflect any new files from batch processing
      },
      error: (err) => { this.handleOperationError('Batch processing', err); this.batchResults = null; this.batchResultPaths = []; }
    });
  }

  onRebuildIndex(): void {
    this.isLoading = true; this.updateTemplateFlags(); // Use isLoading for general loading state
    this.anseriniService?.rebuildIndex().pipe(
      finalize(() => { this.isLoading = false; this.updateTemplateFlags(); })
    ).subscribe({
      next: (res) => this.showSnackbar(res.message || 'Index rebuild initiated successfully!'),
      error: (err) => this.handleOperationError('Rebuild Index', err)
    });
  }

  deleteUploadedFile(fileName: string): void {
    if (confirm(`Are you sure you want to attempt to delete '${fileName}'? This action is frontend-only and might not persist if the backend doesn't support deletion or if the file is managed externally.`)) {
      // For now, only show a snackbar as backend deletion is not specified.
      // If a backend deletion endpoint exists, it should be called here.
      this.showSnackbar(`Deletion for '${fileName}' not fully implemented (no backend call). File might reappear on refresh.`, true, 7000);

      // Optimistic UI update (optional, file will reappear if not deleted on backend and refreshAllData is called)
      // this._uploadedFiles = this._uploadedFiles.filter(f => f !== fileName);
      // this.updateUploadedFilesTable();
    }
  }

  // --- Document Debugger Methods ---

  loadDebuggerStatus(): void {
    this.isLoadingDebuggerStatus = true;
    this.debuggerStatus = null;
    this.updateTemplateFlags();
    this.documentService.getDebuggerStatus().pipe(
      finalize(() => {
        this.isLoadingDebuggerStatus = false;
        this.updateTemplateFlags();
      })
    ).subscribe({
      next: (status) => {
        this.debuggerStatus = status;
      },
      error: (err) => {
        this.handleOperationError('Loading debugger status', err);
        this.debuggerStatus = { // Provide a default error status
          uploadsPathConfigured: false, uploadsPath: 'Error loading status',
          totalLoaders: 0, realLoaders: 0, noOpLoaders: 0,
          totalChunkers: 0, realChunkers: 0, noOpChunkers: 0
        };
      }
    });
  }

  onFileSelectedForDebug(fileName: string): void {
    this.selectedFileForDebug = fileName;
    this.debugAnalysisResult = null;
    this.selectedLoaderForDebug = ''; // Reset loader
    this.selectedChunkerForDebug = ''; // Reset chunker
    this.showDebugAnalysisCard = false;
    this.updateTemplateFlags();
    // Optionally trigger analyzeSelectedFile() here or have a dedicated button
  }

  analyzeSelectedFile(): void {
    if (!this.selectedFileForDebug) {
      this.showSnackbar('Please select a file to analyze from the "Uploaded Files" table.', true);
      return;
    }
    this.isAnalyzingFile = true;
    this.debugAnalysisResult = null;
    this.updateTemplateFlags();

    this.documentService.analyzeFile(
      this.selectedFileForDebug,
      this.selectedLoaderForDebug || undefined, // Pass undefined if empty for auto-select
      this.selectedChunkerForDebug || undefined // Pass undefined if empty for auto-select
    ).pipe(
      finalize(() => {
        this.isAnalyzingFile = false;
        this.updateTemplateFlags();
      })
    ).subscribe({
      next: (result) => {
        this.debugAnalysisResult = result;
        if (result.errorMessage) {
          this.showSnackbar(`Analysis Error: ${result.errorMessage}`, true, 7000);
        } else {
          this.showSnackbar(`Analysis complete for ${result.fileName}.`, false);
        }
      },
      error: (err) => {
        this.handleOperationError(`Analyzing file ${this.selectedFileForDebug}`, err);
        this.debugAnalysisResult = { // Provide a default error result
          fileName: this.selectedFileForDebug, filePath: null, fileSize: 0,
          availableLoaders: null, selectedLoader: null, loadedDocuments: null,
          availableChunkers: null, selectedChunker: null, chunks: null,
          processingStats: null, errorMessage: `Client-side error or unhandled server error: ${err.message || 'Unknown error'}`
        };
      }
    });
  }

  onDebugFileChange(event: Event): void {
    const element = event.currentTarget as HTMLInputElement;
    const fileList: FileList | null = element.files;
    if (fileList && fileList.length > 0) {
      this.debugTestUploadFile = fileList!![0];
    } else {
      this.debugTestUploadFile = null;
    }
  }

  onTestUploadDebugFile(): void {
    if (!this.debugTestUploadFile) {
      this.showSnackbar('Please select a file to upload for debugging.', true);
      return;
    }
    if (!this.debuggerStatus?.uploadsPathConfigured) {
      this.showSnackbar('Test upload unavailable: Debugger uploads path is not configured on the server.', true, 7000);
      return;
    }

    this.isTestingUpload = true;
    this.updateTemplateFlags();
    this.documentService.testUploadDebugFile(this.debugTestUploadFile).pipe(
      finalize(() => {
        this.isTestingUpload = false;
        this.debugTestUploadFile = null;
        this.updateTemplateFlags();
      })
    ).subscribe({
      next: (response: TestUploadResponse) => {
        if (response.error) {
          this.showSnackbar(`Test upload failed: ${response.error}`, true, 7000);
        } else {
          this.showSnackbar(response.message || 'File uploaded successfully for debugging.', false);
          this.refreshAllData(); // Refresh to show the newly uploaded debug file
        }
      },
      error: (err) => {
        this.handleOperationError('Test file upload for debugging', err);
      }
    });
  }

  private handleOperationError(operation: string, error: HttpErrorResponse): void {
    const errMsg = error.error?.error || error.error?.message || (error.error && typeof error.error === 'string' ? error.error : null) || error.message || 'Server error';
    this.showSnackbar(`${operation} failed: ${errMsg}`, true, 7000);
    console.error(`${operation} failed:`, error);
  }

  showSnackbar(message: string, isError: boolean = false, duration: number = 5000): void {
    this.snackBar.open(message, 'Close', {
      duration,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? ['snackbar-error'] : ['snackbar-success']
    });
  }

  getFileName(element: any): string {
    if (typeof element === 'string') {
      return element;
    }
    if (element && typeof element === 'object') {
      return element.name || element.fileName || element.path || String(element);
    }
    return String(element || '');
  }

  getDisplayFileName(element: any): string {
    const fileName = this.getFileName(element);
    return fileName.length > 50 ? fileName.slice(0, 50) + '...' : fileName;
  }

  /**
   * Check if a specific RAM tier is the current system's tier based on max memory.
   */
  isCurrentRamTier(tier: 'lt4' | '4to8' | '8to16' | 'gt16'): boolean {
    if (!this.processingSettings?.memoryStatus?.maxMemoryMB) {
      return false;
    }
    const ramGb = this.processingSettings.memoryStatus.maxMemoryMB / 1024;
    switch (tier) {
      case 'lt4': return ramGb < 4;
      case '4to8': return ramGb >= 4 && ramGb < 8;
      case '8to16': return ramGb >= 8 && ramGb < 16;
      case 'gt16': return ramGb >= 16;
      default: return false;
    }
  }

  /**
   * Get system RAM in GB for display.
   */
  getSystemRamGb(): string {
    if (!this.processingSettings?.memoryStatus?.maxMemoryMB) {
      return '?';
    }
    return (this.processingSettings.memoryStatus.maxMemoryMB / 1024).toFixed(1);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // TASK ENVIRONMENT MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Fetch the ND4J environment snapshot for a task.
   * This is captured at job queue time and useful for debugging.
   */
  private fetchTaskEnvironment(taskId: string): void {
    this.ingestEventService.getTaskEnvironment(taskId).subscribe({
      next: (envResponse) => {
        this.taskEnvironments.set(taskId, envResponse);
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.warn(`Could not fetch environment for task ${taskId}:`, err);
        // Store a placeholder to avoid repeated fetch attempts
        this.taskEnvironments.set(taskId, {
          taskId,
          fileName: '',
          timestamp: '',
          environmentCaptured: false,
          message: 'Environment not available'
        });
      }
    });
  }

  /**
   * Get the environment for a task.
   */
  getTaskEnvironment(taskId: string): TaskEnvironmentResponse | null {
    return this.taskEnvironments.get(taskId) || null;
  }

  /**
   * Check if a task has environment data available.
   */
  hasTaskEnvironment(taskId: string): boolean {
    const env = this.taskEnvironments.get(taskId);
    return env?.environmentCaptured === true;
  }

  /**
   * Toggle environment panel expansion for a task.
   */
  toggleEnvironmentPanel(taskId: string): void {
    if (this.expandedEnvironments.has(taskId)) {
      this.expandedEnvironments.delete(taskId);
    } else {
      this.expandedEnvironments.add(taskId);
    }
  }

  /**
   * Check if environment panel is expanded for a task.
   */
  isEnvironmentExpanded(taskId: string): boolean {
    return this.expandedEnvironments.has(taskId);
  }
}
