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

import { Component, OnInit, OnDestroy, ViewChild, ElementRef, ChangeDetectorRef, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators, FormControl } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatListModule } from '@angular/material/list';
import { MatTabsModule } from '@angular/material/tabs';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSliderModule } from '@angular/material/slider';
import { Subject, Subscription, interval } from 'rxjs';
import { takeUntil, debounceTime, distinctUntilChanged, finalize, filter } from 'rxjs/operators';

import { FactSheetService } from '../../services/fact-sheet.service';
import { DocumentService } from '../../services/document.service';
import { WebSocketService, WebSocketConnectionState } from '../../services/websocket.service';
import { ConfluenceService, ConfluenceIngestRequest } from '../../services/confluence.service';
import { IndexBrowserService } from '../../services/index-browser.service';
import { MainPanelNavigationService } from '../../services/main-panel-navigation.service';
import { SourceViewerService } from '../../services/source-viewer.service';
import { ArchiveService } from '../../services/archive.service';
import { LocalRegistryService, EmbeddingModelStatus } from '../../services/local-registry.service';
import { ModelRegistryService } from '../../services/model-registry.service';
import { SourceViewerDialogComponent, SourceViewerDialogData } from '../source-viewer-dialog/source-viewer-dialog.component';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import {
  FactSheet,
  Fact,
  CreateFactSheetRequest,
  UpdateFactSheetRequest,
  formatFileSize,
  LoaderInfo,
  ChunkerInfo,
  AddSourceDialogResult,
  IngestProgressUpdate,
  IngestPhase,
  IngestStatus,
  IngestStats,
  AsyncUploadResponse,
  BatchAsyncUploadResponse,
  YouTubeTranscriptResponse,
  IngestLogEntry,
  ArchiveModelInfo,
  ArchiveInfo,
  ArchiveStatus,
  ModelSourceType,
  VectorPopulationWorkerStatus,
  VectorPopulationQueueStatus,
  VectorPopulationEmbeddingBatchMetrics,
  VectorPopulationUpdate,
  JobType,
  SubprocessRuntimeInfo,
  ModelStatusUpdate,
  BatchHistoryEntry
} from '../../models/api-models';

// Interface for vector population progress tracking
interface VectorPopulationProgress {
  taskId: string;
  phase: string;
  progressPercent: number;
  message: string;
  currentStep?: string;
  keywordIndexPath?: string;
  vectorIndexPath?: string;
  stats?: {
    documentsLoaded: number;       // From subprocess - actual count from keyword index
    documentsProcessed: number;    // Legacy field
    chunksCreated: number;
    chunksEmbedded: number;
    chunksIndexed: number;
    totalDocuments: number;
    throughputDocsPerSec: number;
    elapsedTimeMs: number;
    actualKeywordIndexCount?: number;
    actualVectorStoreCount?: number;
    workerStatuses?: VectorPopulationWorkerStatus[];
    queueStatus?: VectorPopulationQueueStatus;
    currentEmbeddingBatch?: VectorPopulationEmbeddingBatchMetrics;
    batchHistory?: BatchHistoryEntry[];  // Last N completed batches for UI visibility
    runtimeInfo?: SubprocessRuntimeInfo;
    // Restart tracking
    restartAttempt?: number;
    maxRestartAttempts?: number;
    heapSize?: string;
    heapIncreased?: boolean;
  };
}
import { AddSourceDialogComponent, AddSourceDialogData } from '../document-manager/add-source-dialog/add-source-dialog.component';
import { PipelineSettingsPanelComponent } from '../document-manager/pipeline-settings-panel/pipeline-settings-panel.component';
import { SubprocessLogsComponent } from '../subprocess-logs/subprocess-logs.component';
import { ConnectionsManagerComponent } from '../connections-manager/connections-manager.component';

@Component({
  selector: 'app-fact-sheet-manager',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatProgressBarModule,
    MatSnackBarModule,
    MatDividerModule,
    MatListModule,
    MatTabsModule,
    MatExpansionModule,
    MatTableModule,
    MatPaginatorModule,
    MatCheckboxModule,
    MatSliderModule,
    PipelineSettingsPanelComponent,
    SubprocessLogsComponent,
    ConnectionsManagerComponent,
    ConfirmDialogComponent
  ],
  templateUrl: './fact-sheet-manager.component.html',
  styleUrls: ['./fact-sheet-manager.component.css']
})
export class FactSheetManagerComponent implements OnInit, OnDestroy {
  @ViewChild('fileInput') fileInput!: ElementRef<HTMLInputElement>;
  @ViewChild('keywordPaginator') keywordPaginator!: MatPaginator;
  @ViewChild('vectorPaginator') vectorPaginator!: MatPaginator;

  // Event to open Model Staging tab in parent
  @Output() openModelStaging = new EventEmitter<void>();

  // Backend URL for API calls
  private backendUrl: string;

  // Sheet state
  sheets: FactSheet[] = [];
  activeSheet: FactSheet | null = null;
  isLoading = false;

  // Vector population state
  isVectorPopulating = false;
  currentVectorTaskId: string | null = null;
  vectorPopulationProgress: VectorPopulationProgress | null = null;
  isVectorPopulationLogsExpanded = true;  // Default expanded to show logs
  private wsVectorProgressSub: Subscription | null = null;
  private vectorPopulationEnvironmentRequests: Set<string> = new Set();

  // Embedding subprocess logs visibility
  showEmbeddingLogs = false;

  // Facts state
  facts: Fact[] = [];
  selectedFact: Fact | null = null;
  factsLoading = false;

  // Async upload tracking (same granularity as document-manager)
  activeUploads: Map<string, IngestProgressUpdate> = new Map();
  activeUploadsArray: IngestProgressUpdate[] = [];
  wsConnectionState: WebSocketConnectionState = WebSocketConnectionState.DISCONNECTED;

  // Completed jobs notification pane - shows recently completed jobs until dismissed
  completedJobs: IngestProgressUpdate[] = [];
  expandedPipelines: Set<string> = new Set();
  expandedBatchDetails: Set<string> = new Set();
  expandedJobInfo: Set<string> = new Set();
  expandedSubprocessDetails: Set<string> = new Set();
  expandedSubprocessLogs: Set<string> = new Set();
  taskLogs: Map<string, IngestLogEntry[]> = new Map();

  // UI state for environment panels
  expandedEnvironment: Set<string> = new Set();
  showRawJson: Set<string> = new Set();

  toggleEnvironment(taskId: string): void {
    if (this.expandedEnvironment.has(taskId)) {
      this.expandedEnvironment.delete(taskId);
    } else {
      this.expandedEnvironment.add(taskId);
    }
  }

  toggleRawJson(taskId: string): void {
    if (this.showRawJson.has(taskId)) {
      this.showRawJson.delete(taskId);
    } else {
      this.showRawJson.add(taskId);
    }
  }

  isEnvironmentExpanded(taskId: string): boolean {
    return this.expandedEnvironment.has(taskId);
  }

  isRawJsonVisible(taskId: string): boolean {
    return this.showRawJson.has(taskId);
  }
  private cancelledTaskIds: Set<string> = new Set();
  private wsSubscription: Subscription | null = null;
  private logsSubscription: Subscription | null = null;
  private connectionSubscription: Subscription | null = null;
  private taskPollingSubscription: Subscription | null = null;
  private modelStatusSubscribed = false;

  // Tab state
  selectedTabIndex = 0;

  // Create/Edit form
  showCreateForm = false;
  showEditForm = false;
  sheetForm: FormGroup;
  editingSheet: FactSheet | null = null;

  // Available colors and icons
  colors = [
    { value: '#1976d2', name: 'Blue' },
    { value: '#388e3c', name: 'Green' },
    { value: '#d32f2f', name: 'Red' },
    { value: '#7b1fa2', name: 'Purple' },
    { value: '#f57c00', name: 'Orange' },
    { value: '#0097a7', name: 'Cyan' },
    { value: '#5d4037', name: 'Brown' },
    { value: '#455a64', name: 'Gray' }
  ];

  icons = [
    { value: 'folder', name: 'Folder' },
    { value: 'description', name: 'Document' },
    { value: 'library_books', name: 'Library' },
    { value: 'science', name: 'Science' },
    { value: 'work', name: 'Work' },
    { value: 'school', name: 'School' },
    { value: 'favorite', name: 'Favorite' },
    { value: 'star', name: 'Star' }
  ];

  // Available loaders and chunkers for the add source dialog
  availableLoaders: LoaderInfo[] = [];
  availableChunkers: ChunkerInfo[] = [];

  // Index Browser state
  indexerStatus: any = null;
  statusLoading = true;
  keywordDocuments: any[] = [];
  vectorDocuments: any[] = [];
  keywordDataSource = new MatTableDataSource<any>();
  vectorDataSource = new MatTableDataSource<any>();
  keywordDisplayedColumns = ['id', 'content', 'score', 'originalDoc', 'actions'];
  vectorDisplayedColumns = ['id', 'content', 'score', 'originalDoc', 'actions'];
  keywordSearchQuery = '';
  vectorSearchQuery = '';
  keywordTotalResults = 0;
  vectorTotalResults = 0;
  keywordPageSize = 10;
  vectorPageSize = 10;
  keywordCurrentPage = 0;
  vectorCurrentPage = 0;
  keywordSearchLoading = false;
  vectorSearchLoading = false;
  similarityThreshold = 0.5;
  selectedDocument: any = null;
  keywordSearchControl = new FormControl('');
  vectorSearchControl = new FormControl('');

  // Index Storage Settings (per-sheet)
  editVectorStorePath = '';
  editKeywordIndexPath = '';
  isIndexStorageSaving = false;
  indexStorageSaveMessage: string | null = null;

  // Model Configuration (per-sheet)
  encoderOptions: ArchiveModelInfo[] = [];
  crossEncoderOptions: ArchiveModelInfo[] = [];
  isArchiveLoaded = false;
  isModelConfigSaving = false;
  modelConfigSaveMessage: string | null = null;

  // Available Archives and Active Models
  availableArchives: ArchiveInfo[] = [];
  currentArchiveStatus: ArchiveStatus | null = null;
  allArchiveModels: ArchiveModelInfo[] = [];
  isLoadingArchives = false;

  // System-wide loaded configuration (from backend)
  systemConfig: {
    embeddingModel: { className: string; dimensions: number; isNoOp: boolean; modelId?: string } | null;
    reranker: { available: boolean; className: string; supportedTypes: string[] } | null;
    vectorStore: { className: string; available: boolean } | null;
  } = { embeddingModel: null, reranker: null, vectorStore: null };
  isLoadingSystemConfig = false;

  // Embedding model status (includes source: REGISTRY or ARCHIVE)
  embeddingStatus: EmbeddingModelStatus | null = null;

  // Model config form fields
  editEmbeddingModel: string | null = null;
  editEmbeddingModelSource: ModelSourceType = 'default';
  editEmbeddingArchiveId: string | null = null;
  editRerankingEnabled = false;
  editRerankerType: string | null = 'none';
  editCrossEncoderModel: string | null = null;
  editCrossEncoderModelSource: ModelSourceType = 'default';
  editCrossEncoderArchiveId: string | null = null;
  editRerankTopK: number | null = 10;
  editMmrLambda: number | null = 0.5;

  // Available reranker types
  rerankerTypes = [
    { value: 'none', label: 'None (No reranking)' },
    { value: 'cross_encoder', label: 'Cross-Encoder' },
    { value: 'rrf', label: 'Reciprocal Rank Fusion (RRF)' },
    { value: 'mmr', label: 'Maximal Marginal Relevance (MMR)' },
    { value: 'rm3', label: 'RM3 Query Expansion' },
    { value: 'bm25prf', label: 'BM25 Pseudo Relevance Feedback' }
  ];

  // Model source options
  modelSourceOptions: { value: ModelSourceType; label: string }[] = [
    { value: 'default', label: 'Default (System Config)' },
    { value: 'archive', label: 'From Archive' },
    { value: 'registry', label: 'From Registry' }
  ];

  // Knowledge Graph Configuration (per-sheet)
  editEnableGraphBuilding = false;
  editGraphBuilderType: string = 'llm';
  editGraphStorageType: string = 'jpa';
  isGraphConfigSaving = false;
  graphConfigSaveMessage: string | null = null;
  availableStorageTypes: { type: string; available: boolean; description: string }[] = [];

  // Graph builder type options
  graphBuilderTypes = [
    { value: 'manual', label: 'Manual (UI-based)' },
    { value: 'llm', label: 'LLM-based Extraction' },
    { value: 'pattern', label: 'Pattern-based (Regex/NLP)' },
    { value: 'hybrid', label: 'Hybrid (Combined)' }
  ];

  // Graph storage type options (will be filtered by availability)
  graphStorageTypes = [
    { value: 'jpa', label: 'Local Database (JPA/JDBC)', description: 'Store graph data in the local database' },
    { value: 'neo4j', label: 'Neo4j', description: 'Store graph data in Neo4j graph database' }
  ];

  private destroy$ = new Subject<void>();

  constructor(
    private factSheetService: FactSheetService,
    private documentService: DocumentService,
    private webSocketService: WebSocketService,
    private confluenceService: ConfluenceService,
    private indexBrowserService: IndexBrowserService,
    private mainPanelNavigationService: MainPanelNavigationService,
    private sourceViewerService: SourceViewerService,
    private archiveService: ArchiveService,
    private localRegistryService: LocalRegistryService,
    private modelRegistryService: ModelRegistryService,
    private http: HttpClient,
    private fb: FormBuilder,
    private snackBar: MatSnackBar,
    private dialog: MatDialog,
    private cdr: ChangeDetectorRef,
    private router: Router
  ) {
    // Initialize backend URL
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.backendUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
    } else {
      this.backendUrl = '/api';
    }

    this.sheetForm = this.fb.group({
      name: ['', [Validators.required, Validators.minLength(1), Validators.maxLength(255)]],
      description: ['', Validators.maxLength(1024)],
      color: ['#1976d2'],
      icon: ['folder']
    });
  }

  ngOnInit(): void {
    // Set up subscriptions FIRST to ensure we don't miss any emissions
    // Subscribe to sheets
    this.factSheetService.sheets$
      .pipe(takeUntil(this.destroy$))
      .subscribe(sheets => this.sheets = sheets);

    // Subscribe to active sheet
    this.factSheetService.activeSheet$
      .pipe(takeUntil(this.destroy$))
      .subscribe(sheet => {
        const previousSheetId = this.activeSheet?.id;
        const sheetChanged = previousSheetId !== sheet?.id;
        this.activeSheet = sheet;
        if (sheet) {
          this.loadFacts();
          // Update index storage edit fields when active sheet changes
          this.editVectorStorePath = sheet.vectorStorePath || '';
          this.editKeywordIndexPath = sheet.keywordIndexPath || '';
          // Update model config edit fields when active sheet changes
          this.updateModelConfigFormFromSheet(sheet);
        }

        // Always recompute filtered uploads when sheet changes to ensure UI stays in sync
        if (sheetChanged) {
          console.log(`[FactSheetManager] Sheet changed from ${previousSheetId} to ${sheet?.id}, updating filtered uploads`);
          this.updateFilteredUploadsArray();

          // Refresh index browser status when switching sheets
          // This updates storage location and document counts for the new sheet
          this.loadIndexerStatus();

          // Refresh archive status to ensure archive loading display is up to date
          this.refreshArchiveStatus();

          // Refresh system config to show correct encoder/reranker for this sheet
          this.loadSystemConfig();
        }
      });

    // Navigate to the main facts panel when requested by other components
    this.mainPanelNavigationService.focusMainPanel$
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        this.selectedTabIndex = 0;
        this.cdr.detectChanges();
      });

    // Now load data after subscriptions are ready
    this.loadData();
    this.loadAvailableLoadersAndChunkers();
    this.loadActiveIngestTasks();
    this.initializeWebSocket();
    this.subscribeToVectorProgress();
    this.restoreActiveVectorPopulationState();
    this.startTaskPolling();
    this.loadIndexerStatus();
    this.loadArchiveModels();
    this.loadSystemConfig();
    this.loadAvailableStorageTypes();

    // Subscribe to facts
    this.factSheetService.facts$
      .pipe(takeUntil(this.destroy$))
      .subscribe(facts => this.facts = facts);

    // Subscribe to model registry changes to refresh UI when models change
    this.modelRegistryService.changes$
      .pipe(takeUntil(this.destroy$))
      .subscribe(event => {
        console.log('[FactSheetManager] Model registry change:', event.type, event.modelId);
        // Refresh embedding status and system config when models change
        this.loadSystemConfig();
        this.loadIndexerStatus();
        this.cdr.markForCheck();
      });

    // Subscribe to keyword search input
    this.keywordSearchControl.valueChanges.pipe(
      debounceTime(400),
      distinctUntilChanged(),
      takeUntil(this.destroy$)
    ).subscribe(value => {
      this.keywordSearchQuery = value || '';
      if (this.keywordSearchQuery.length >= 2) {
        this.searchKeywordIndex();
      }
    });

    // Subscribe to vector search input
    this.vectorSearchControl.valueChanges.pipe(
      debounceTime(400),
      distinctUntilChanged(),
      takeUntil(this.destroy$)
    ).subscribe(value => {
      this.vectorSearchQuery = value || '';
      if (this.vectorSearchQuery.length >= 2) {
        this.searchVectorStore();
      }
    });

    // Subscribe to WebSocket model status updates for real-time UI updates
    this.subscribeToModelStatusUpdates();
  }

  /**
   * Subscribe to WebSocket model status updates for real-time UI updates.
   * This provides push-based updates when embedding model status changes.
   */
  private subscribeToModelStatusUpdates(): void {
    if (this.modelStatusSubscribed) {
      return;
    }

    this.modelStatusSubscribed = true;

    this.webSocketService.subscribeToModelStatus().pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (status: ModelStatusUpdate) => {
        this.handleModelStatusUpdate(status);
      },
      error: (err) => {
        console.error('[WS-MODEL] Error in model status WebSocket:', err);
      }
    });

    console.log('[WS-MODEL] FactSheetManager subscribed to model status updates');
  }

  /**
   * Handle incoming model status updates from WebSocket.
   */
  private handleModelStatusUpdate(status: ModelStatusUpdate): void {
    if (!status.embedding) return;

    const isNowInitialized = status.embedding.initialized && (status.embedding.dimensions || 0) > 0;

    // Check if model just became ready (wasn't available before, now is)
    const wasAvailable = this.systemConfig?.embeddingModel &&
      !this.systemConfig.embeddingModel.isNoOp &&
      this.systemConfig.embeddingModel.dimensions > 0;

    if (!wasAvailable && isNowInitialized) {
      console.log('[WS-MODEL] Embedding model is now ready in FactSheetManager!');
      this.snackBar.open('Embedding model is now ready!', 'Close', {
        duration: 5000,
        panelClass: ['snackbar-success']
      });
      // Refresh full system config to get accurate info
      this.loadSystemConfig();
      this.loadIndexerStatus();
    } else if (status.embedding.loading !== undefined) {
      // Model status changed (loading state), refresh config
      this.loadSystemConfig();
    }

    this.cdr.markForCheck();
  }

  loadAvailableLoadersAndChunkers(): void {
    this.documentService.getAvailableLoaders().subscribe({
      next: (loaders) => this.availableLoaders = loaders,
      error: () => console.warn('Failed to load available loaders')
    });
    this.documentService.getAvailableChunkers().subscribe({
      next: (chunkers) => this.availableChunkers = chunkers,
      error: () => console.warn('Failed to load available chunkers')
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.wsSubscription?.unsubscribe();
    this.logsSubscription?.unsubscribe();
    this.connectionSubscription?.unsubscribe();
    this.taskPollingSubscription?.unsubscribe();
    this.unsubscribeFromVectorProgress();
    // Unsubscribe from model status WebSocket
    this.webSocketService.unsubscribeFromModelStatus();
    this.webSocketService.disconnect();
  }

  loadData(): void {
    this.isLoading = true;
    this.factSheetService.loadSheets().subscribe({
      next: () => {
        this.factSheetService.loadActiveSheet().subscribe({
          next: () => this.isLoading = false,
          error: () => this.isLoading = false
        });
      },
      error: () => {
        this.isLoading = false;
        this.showError('Failed to load fact sheets');
      }
    });
  }

  loadFacts(): void {
    this.factsLoading = true;
    this.factSheetService.loadActiveFacts().subscribe({
      next: () => this.factsLoading = false,
      error: () => {
        this.factsLoading = false;
        this.showError('Failed to load facts');
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // WEBSOCKET & PROGRESS TRACKING (same granularity as document-manager)
  // ═══════════════════════════════════════════════════════════════════════════════

  private initializeWebSocket(): void {
    // Subscribe to connection state
    this.connectionSubscription = this.webSocketService.connectionState$.subscribe(state => {
      const previousState = this.wsConnectionState;
      this.wsConnectionState = state;

      // Reload active tasks when WebSocket connects/reconnects
      if (state === WebSocketConnectionState.CONNECTED && previousState !== WebSocketConnectionState.CONNECTED) {
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
    this.documentService.getAllIngestTasks().subscribe({
      next: (tasks) => {
        tasks.forEach(task => {
          // Merge with existing data to preserve accumulated stats from WebSocket updates
          const existing = this.activeUploads.get(task.taskId);
          const merged = this.mergeProgressUpdate(existing, task);
          this.activeUploads.set(task.taskId, merged);
          // Schedule removal for completed/failed tasks
          if (task.status === IngestStatus.COMPLETED || task.status === IngestStatus.FAILED) {
            setTimeout(() => {
              this.activeUploads.delete(task.taskId);
              this.updateFilteredUploadsArray();
            }, 10000);
          }
        });
        this.updateFilteredUploadsArray();
      },
      error: (err) => console.warn('Could not load active ingest tasks:', err)
    });
  }

  private startTaskPolling(): void {
    this.taskPollingSubscription = interval(2000).subscribe(() => {
      const hasActiveUploads = this.hasActiveProcessing();
      const wsNotConnected = this.wsConnectionState !== WebSocketConnectionState.CONNECTED;

      if (hasActiveUploads || wsNotConnected) {
        this.documentService.getAllIngestTasks().subscribe({
          next: (tasks) => {
            let hasChanges = false;
            tasks.forEach(task => {
              if (this.cancelledTaskIds.has(task.taskId)) return;

              const existing = this.activeUploads.get(task.taskId);
              if (!existing || existing.progressPercent !== task.progressPercent ||
                existing.phase !== task.phase || existing.status !== task.status) {
                // Use mergeProgressUpdate to preserve accumulated stats from WebSocket updates
                const merged = this.mergeProgressUpdate(existing, task);
                this.activeUploads.set(task.taskId, merged);
                hasChanges = true;

                if (task.status === IngestStatus.COMPLETED || task.status === IngestStatus.FAILED || task.status === IngestStatus.CANCELLED) {
                  setTimeout(() => {
                    this.activeUploads.delete(task.taskId);
                    this.cancelledTaskIds.delete(task.taskId);
                    this.updateFilteredUploadsArray();
                  }, 10000);

                  if (task.status === IngestStatus.COMPLETED && (!existing || existing.status !== IngestStatus.COMPLETED)) {
                    this.loadFacts();
                    this.factSheetService.loadSheets().subscribe();
                    this.showSuccess(`'${task.fileName}' processed successfully!`);
                  }
                }
              }
            });

            const backendTaskIds = new Set(tasks.map(t => t.taskId));
            this.activeUploads.forEach((_, taskId) => {
              if (!backendTaskIds.has(taskId)) {
                this.activeUploads.delete(taskId);
                this.cancelledTaskIds.delete(taskId);
                hasChanges = true;
              }
            });

            if (hasChanges) {
              this.updateFilteredUploadsArray();
            }
          }
        });
      }
    });
  }

  private handleProgressUpdate(update: IngestProgressUpdate): void {
    // Skip non-cancelled updates for locally-cancelled tasks
    if (this.cancelledTaskIds.has(update.taskId) && update.status !== IngestStatus.CANCELLED) {
      return;
    }

    // Log incoming update for debugging
    console.log(`[FactSheetManager] Received progress update: taskId=${update.taskId.substring(0, 8)}, factSheetId=${update.factSheetId}, status=${update.status}, activeSheetId=${this.activeSheet?.id}`);

    const existing = this.activeUploads.get(update.taskId);
    const merged = this.mergeProgressUpdate(existing, update);
    this.activeUploads.set(update.taskId, merged);

    // Auto-expand logs and job info panel for new tasks so users can see real-time logs immediately
    if (!existing && update.status === IngestStatus.IN_PROGRESS) {
      this.expandedSubprocessLogs.add(update.taskId);
      this.expandedJobInfo.add(update.taskId);
    }

    this.updateFilteredUploadsArray();

    // Handle completed/failed/cancelled tasks
    if (update.status === IngestStatus.COMPLETED ||
      update.status === IngestStatus.FAILED ||
      update.status === IngestStatus.CANCELLED) {

      // Add to completed jobs notification pane (if not already there)
      const alreadyInCompleted = this.completedJobs.some(j => j.taskId === update.taskId);
      if (!alreadyInCompleted) {
        // Add completed job with completion timestamp
        const completedJob = { ...merged, completedAt: new Date().toISOString() };
        this.completedJobs.unshift(completedJob); // Add to beginning

        // Auto-dismiss completed jobs after 60 seconds
        setTimeout(() => {
          this.dismissCompletedJob(update.taskId);
        }, 60000);
      }

      // Remove from active uploads after a short delay
      setTimeout(() => {
        this.activeUploads.delete(update.taskId);
        this.cancelledTaskIds.delete(update.taskId);
        this.updateFilteredUploadsArray();
      }, 2000); // Reduced delay since we now have the completed pane

      // Refresh facts on completion
      if (update.status === IngestStatus.COMPLETED) {
        this.loadFacts();
        this.factSheetService.loadSheets().subscribe();
      } else if (update.status === IngestStatus.FAILED) {
        this.showError(`Failed to process '${update.fileName}': ${update.errorMessage}`);
      }
    }

    this.cdr.detectChanges();
  }

  private handleLogEntry(logEntry: IngestLogEntry): void {
    if (!logEntry || !logEntry.taskId) return;

    if (!this.taskLogs.has(logEntry.taskId)) {
      this.taskLogs.set(logEntry.taskId, []);
    }

    const logs = this.taskLogs.get(logEntry.taskId)!;
    logs.push(logEntry);
    if (logs.length > 200) {
      logs.shift();
    }
    this.cdr.detectChanges();
  }

  private mergeProgressUpdate(existing: IngestProgressUpdate | undefined, update: IngestProgressUpdate): IngestProgressUpdate {
    if (!existing) return { ...update };

    let mergedStats = update.stats;
    if (existing.stats && update.stats) {
      const mergedRuntimeInfo = existing.stats.subprocessRuntimeInfo && update.stats.subprocessRuntimeInfo
        ? { ...existing.stats.subprocessRuntimeInfo, ...update.stats.subprocessRuntimeInfo }
        : update.stats.subprocessRuntimeInfo || existing.stats.subprocessRuntimeInfo;

      // Merge batch history - accumulate unique batches instead of replacing
      let mergedBatchHistory = update.stats.batchHistory || existing.stats.batchHistory;
      if (existing.stats.batchHistory && update.stats.batchHistory) {
        // Combine and dedupe by batch number, keeping newer entries
        const batchMap = new Map<number, any>();
        for (const batch of existing.stats.batchHistory) {
          if (batch?.batchNumber !== undefined) {
            batchMap.set(batch.batchNumber, batch);
          }
        }
        for (const batch of update.stats.batchHistory) {
          if (batch?.batchNumber !== undefined) {
            batchMap.set(batch.batchNumber, batch);
          }
        }
        mergedBatchHistory = Array.from(batchMap.values()).sort((a, b) => a.batchNumber - b.batchNumber);
      }

      // Merge current embedding batch - preserve higher batch number during restarts
      let mergedCurrentBatch = update.stats.currentEmbeddingBatch || existing.stats.currentEmbeddingBatch;
      if (existing.stats.currentEmbeddingBatch && update.stats.currentEmbeddingBatch) {
        const existingBatchNum = existing.stats.currentEmbeddingBatch.batchNumber || 0;
        const updateBatchNum = update.stats.currentEmbeddingBatch.batchNumber || 0;
        // Use the batch info with higher processed count to handle restarts properly
        const existingProcessed = existing.stats.chunksEmbedded ?? 0;
        const updateProcessed = update.stats.chunksEmbedded ?? 0;
        if (updateProcessed >= existingProcessed) {
          mergedCurrentBatch = update.stats.currentEmbeddingBatch;
        } else {
          mergedCurrentBatch = existing.stats.currentEmbeddingBatch;
        }
      }

      mergedStats = {
        ...existing.stats,
        ...update.stats,
        // Use Math.max to ensure progress only accumulates (never decreases)
        documentsLoaded: Math.max(update.stats.documentsLoaded ?? 0, existing.stats.documentsLoaded ?? 0),
        chunksCreated: Math.max(update.stats.chunksCreated ?? 0, existing.stats.chunksCreated ?? 0),
        chunksEmbedded: Math.max(update.stats.chunksEmbedded ?? 0, existing.stats.chunksEmbedded ?? 0),
        chunksIndexed: Math.max(update.stats.chunksIndexed ?? 0, existing.stats.chunksIndexed ?? 0),
        documentsIndexed: Math.max(update.stats.documentsIndexed ?? 0, existing.stats.documentsIndexed ?? 0),
        queueStatus: update.stats.queueStatus || existing.stats.queueStatus,
        currentEmbeddingBatch: mergedCurrentBatch,
        batchHistory: mergedBatchHistory,
        subprocessRuntimeInfo: mergedRuntimeInfo,
        workerStatuses: update.stats.workerStatuses || existing.stats.workerStatuses,
        // Preserve restart information
        restartAttempt: Math.max(update.stats.restartAttempt ?? 0, existing.stats.restartAttempt ?? 0),
        maxRestartAttempts: update.stats.maxRestartAttempts ?? existing.stats.maxRestartAttempts,
        heapSize: update.stats.heapSize || existing.stats.heapSize,
      };
    } else if (existing.stats && !update.stats) {
      mergedStats = existing.stats;
    }

    // Preserve factSheetId - use update's value if present, otherwise keep existing
    const mergedFactSheetId = update.factSheetId !== null && update.factSheetId !== undefined
      ? update.factSheetId
      : existing.factSheetId;

    // Use Math.max for progressPercent to prevent regression during restarts
    const mergedProgressPercent = Math.max(update.progressPercent ?? 0, existing.progressPercent ?? 0);

    return { ...existing, ...update, stats: mergedStats, factSheetId: mergedFactSheetId, progressPercent: mergedProgressPercent };
  }

  /**
   * Updates the activeUploadsArray to show only uploads for the current active sheet.
   * Jobs with null factSheetId are shown on all sheets (backwards compatibility).
   */
  private updateFilteredUploadsArray(): void {
    const activeSheetId = this.activeSheet?.id;
    const allUploads = Array.from(this.activeUploads.values());

    const filtered = allUploads.filter(u => {
      // Show task if:
      // 1. It belongs to the current active sheet
      // 2. OR it has no factSheetId (backwards compatibility - show on all sheets)
      const belongsToSheet = u.factSheetId === activeSheetId;
      const hasNoSheetId = u.factSheetId === null || u.factSheetId === undefined;
      return belongsToSheet || hasNoSheetId;
    });

    console.log(`[FactSheetManager] updateFilteredUploadsArray: activeSheetId=${activeSheetId}, total=${allUploads.length}, filtered=${filtered.length}`);
    if (allUploads.length > 0) {
      console.log(`[FactSheetManager] All uploads factSheetIds:`, allUploads.map(u => ({ taskId: u.taskId.substring(0, 8), factSheetId: u.factSheetId, status: u.status })));
    }

    this.activeUploadsArray = filtered.map(u => ({ ...u }));
    this.cdr.markForCheck();
    this.cdr.detectChanges();
  }

  // Progress tracking helpers
  hasActiveProcessing(): boolean {
    // Include PENDING status to ensure polling catches newly-queued tasks
    // that might have missed their initial WebSocket event
    return this.activeUploadsArray.some(u =>
      u.status === IngestStatus.IN_PROGRESS || u.status === IngestStatus.PENDING
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // COMPLETED JOBS NOTIFICATION PANE
  // ═══════════════════════════════════════════════════════════════════════════════

  /** Dismiss a single completed job from the notification pane */
  dismissCompletedJob(taskId: string): void {
    this.completedJobs = this.completedJobs.filter(j => j.taskId !== taskId);
    this.cdr.detectChanges();
  }

  /** Dismiss all completed jobs from the notification pane */
  dismissAllCompletedJobs(): void {
    this.completedJobs = [];
    this.cdr.detectChanges();
  }

  /** Get completed jobs for the current active sheet */
  getCompletedJobsForCurrentSheet(): IngestProgressUpdate[] {
    const activeSheetId = this.activeSheet?.id;
    return this.completedJobs.filter(j =>
      j.factSheetId === activeSheetId || j.factSheetId === null || j.factSheetId === undefined
    );
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

  getSourceTypeMessage(sourceType: string, phase: IngestPhase): string {
    const phaseMessages: { [key: string]: { [key: string]: string } } = {
      'file': {
        'QUEUED': 'Queued for processing',
        'UPLOADING': 'Uploading file...',
        'LOADING': 'Loading document...',
        'OCR_PROCESSING': 'OCR processing pages...',
        'CHUNKING': 'Chunking content...',
        'EMBEDDING': 'Generating embeddings...',
        'INDEXING': 'Indexing...',
        'COMPLETED': 'Upload complete',
        'FAILED': 'Upload failed'
      },
      'url': {
        'QUEUED': 'Queued for fetching',
        'LOADING': 'Fetching URL...',
        'CHUNKING': 'Chunking content...',
        'EMBEDDING': 'Generating embeddings...',
        'INDEXING': 'Indexing...',
        'COMPLETED': 'URL processed',
        'FAILED': 'Failed to fetch URL'
      },
      'youtube': {
        'QUEUED': 'Queued for transcript fetch',
        'LOADING': 'Fetching transcript...',
        'CHUNKING': 'Chunking transcript...',
        'EMBEDDING': 'Generating embeddings...',
        'INDEXING': 'Indexing...',
        'COMPLETED': 'Transcript processed',
        'FAILED': 'Failed to fetch transcript'
      },
      'confluence': {
        'QUEUED': 'Queued for Confluence sync',
        'LOADING': 'Fetching pages...',
        'CHUNKING': 'Chunking pages...',
        'EMBEDDING': 'Generating embeddings...',
        'INDEXING': 'Indexing...',
        'COMPLETED': 'Confluence sync complete',
        'FAILED': 'Confluence sync failed'
      },
      'slack': {
        'QUEUED': 'Queued for Slack sync',
        'LOADING': 'Fetching messages...',
        'CHUNKING': 'Processing messages...',
        'EMBEDDING': 'Generating embeddings...',
        'INDEXING': 'Indexing...',
        'COMPLETED': 'Slack sync complete',
        'FAILED': 'Slack sync failed'
      },
      'text': {
        'QUEUED': 'Queued for processing',
        'LOADING': 'Processing text...',
        'CHUNKING': 'Chunking text...',
        'EMBEDDING': 'Generating embeddings...',
        'INDEXING': 'Indexing...',
        'COMPLETED': 'Text processed',
        'FAILED': 'Text processing failed'
      }
    };

    const typeMessages = phaseMessages[sourceType?.toLowerCase()] || phaseMessages['file'];
    return typeMessages[phase] || `${phase}...`;
  }

  isPhaseActive(upload: IngestProgressUpdate, phase: string): boolean {
    return String(upload.phase) === phase;
  }

  isPhaseComplete(upload: IngestProgressUpdate, phase: string): boolean {
    const phaseOrder = ['QUEUED', 'LOADING', 'OCR_PROCESSING', 'CONVERTING', 'CHUNKING', 'EMBEDDING', 'INDEXING', 'COMPLETED'];
    const currentPhase = String(upload.phase);
    const currentIndex = phaseOrder.indexOf(currentPhase);
    const checkIndex = phaseOrder.indexOf(phase);
    return currentIndex > checkIndex || currentPhase === 'COMPLETED';
  }

  canCancelTask(upload: IngestProgressUpdate): boolean {
    return upload.status === IngestStatus.IN_PROGRESS || upload.status === IngestStatus.PENDING;
  }

  cancelIngestTask(taskId: string): void {
    const localTask = this.activeUploads.get(taskId);

    if (localTask && (localTask.status === IngestStatus.COMPLETED ||
      localTask.status === IngestStatus.FAILED ||
      localTask.status === IngestStatus.CANCELLED)) {
      this.showError(`Task already ${localTask.status.toLowerCase()}`);
      return;
    }

    if (localTask && this.isVectorPopulationTask(localTask)) {
      this.indexBrowserService.cancelVectorPopulation(taskId).subscribe({
        next: (response: any) => {
          const message = response?.message || 'Cancellation requested...';
          this.showSuccess(message);
          this.cancelledTaskIds.add(taskId);

          const updatedTask: IngestProgressUpdate = {
            ...(localTask || {
              taskId,
              fileName: 'Vector Population',
              phase: IngestPhase.COMPLETED,
              status: IngestStatus.CANCELLED,
              progressPercent: 0,
              currentStep: 'Stopping',
              message: 'Cancellation requested...',
              stats: null,
              errorMessage: null,
              timestamp: new Date().toISOString(),
              factSheetId: this.activeSheet?.id || null,
              jobType: 'VECTOR_POPULATION'
            }),
            status: IngestStatus.CANCELLED,
            message: message,
            currentStep: 'Stopping'
          };
          this.activeUploads.set(taskId, updatedTask);
          this.updateFilteredUploadsArray();

          setTimeout(() => {
            this.activeUploads.delete(taskId);
            this.cancelledTaskIds.delete(taskId);
            this.updateFilteredUploadsArray();
          }, 5000);
        },
        error: (err) => {
          this.showError(err.message || 'Failed to cancel vector population task');
        }
      });
      return;
    }

    this.documentService.cancelIngestTask(taskId).subscribe({
      next: (response) => {
        const fileName = localTask?.fileName || `task ${taskId.substring(0, 8)}...`;
        if (response.cancelled) {
          this.showSuccess(`Cancelling '${fileName}'...`);
          this.cancelledTaskIds.add(taskId);

          if (localTask) {
            const updatedTask: IngestProgressUpdate = {
              ...localTask,
              status: IngestStatus.CANCELLED,
              message: 'Cancellation requested...',
              currentStep: 'Stopping'
            };
            this.activeUploads.set(taskId, updatedTask);
            this.updateFilteredUploadsArray();
          }

          setTimeout(() => {
            this.activeUploads.delete(taskId);
            this.cancelledTaskIds.delete(taskId);
            this.updateFilteredUploadsArray();
          }, 5000);
        } else {
          this.showError(response.message || 'Could not cancel task');
        }
      },
      error: (err) => {
        if (err.status === 404) {
          this.showError('Task not found on server');
          this.activeUploads.delete(taskId);
          this.cancelledTaskIds.delete(taskId);
          this.updateFilteredUploadsArray();
        } else {
          this.showError('Failed to cancel task');
        }
      }
    });
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

  getTaskLogs(taskId: string): IngestLogEntry[] {
    return this.taskLogs.get(taskId) || [];
  }

  // Job Info panel toggle
  toggleJobInfo(taskId: string): void {
    if (this.expandedJobInfo.has(taskId)) {
      this.expandedJobInfo.delete(taskId);
    } else {
      this.expandedJobInfo.add(taskId);
    }
    this.cdr.markForCheck();
  }

  isJobInfoExpanded(taskId: string): boolean {
    return this.expandedJobInfo.has(taskId);
  }

  // Subprocess details toggle
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

  // Subprocess logs toggle
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

  // Worker status helpers
  getWorkersByType(workers: any[], type: string): any[] {
    return workers.filter(w => w.workerType === type);
  }

  getTotalProcessed(workers: any[], type: string): number {
    return workers
      .filter(w => w.workerType === type)
      .reduce((sum, w) => sum + (w.itemsProcessed || 0), 0);
  }

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
    const indexed = this.getIndexingDisplayCount(upload);
    return Math.round((indexed / upload.stats.chunksCreated) * 100);
  }

  isVectorPopulationTask(upload: IngestProgressUpdate): boolean {
    const name = upload?.fileName || '';
    return upload?.jobType === 'VECTOR_POPULATION' || name.toLowerCase().startsWith('vector population');
  }

  getIndexingDisplayCount(upload: IngestProgressUpdate): number {
    if (!upload.stats) return 0;
    if (this.isVectorPopulationTask(upload)) {
      return upload.stats.chunksEmbedded || 0;
    }
    return upload.stats.documentsIndexed || upload.stats.chunksIndexed || 0;
  }

  getIndexingDisplayInProgress(upload: IngestProgressUpdate): number {
    if (this.isVectorPopulationTask(upload)) {
      return this.getEmbeddingInProgress(upload);
    }
    return this.getIndexingInProgress(upload);
  }

  isVectorIndexingActive(upload: IngestProgressUpdate): boolean {
    if (this.isVectorPopulationTask(upload)) {
      return this.isEmbeddingActive(upload) || this.hasEmbeddingQueueItems(upload);
    }
    return this.isIndexingActive(upload);
  }

  getIndexingQueueSize(upload: IngestProgressUpdate): number {
    if (this.isVectorPopulationTask(upload)) {
      return upload.stats?.queueStatus?.chunkQueueSize || 0;
    }
    return upload.stats?.queueStatus?.embeddedQueueSize || 0;
  }

  // Detect passthrough mode: embedding is done by indexer
  isPassthroughMode(upload: IngestProgressUpdate): boolean {
    if (!upload.stats) return false;
    const embedded = upload.stats.chunksEmbedded || 0;
    const indexed = upload.stats.chunksIndexed || 0;
    return embedded === 0 && indexed > 0;
  }

  // Get total items currently being processed by indexing workers
  getIndexingInProgress(upload: IngestProgressUpdate): number {
    if (!upload.stats?.workerStatuses) return 0;
    return upload.stats.workerStatuses
      .filter(w => w.workerType === 'indexing' && w.status === 'processing')
      .reduce((sum, w) => sum + (w.currentBatchSize || 0), 0);
  }

  // Get total items currently being processed by embedding workers
  getEmbeddingInProgress(upload: IngestProgressUpdate): number {
    if (!upload.stats?.workerStatuses) return 0;
    return upload.stats.workerStatuses
      .filter(w => w.workerType === 'embedding' && w.status === 'processing')
      .reduce((sum, w) => sum + (w.currentBatchSize || 0), 0);
  }

  // Check if embedding workers are actively processing (not just waiting)
  isEmbeddingActive(upload: IngestProgressUpdate): boolean {
    if (!upload.stats?.workerStatuses) return false;
    return upload.stats.workerStatuses.some(
      w => w.workerType === 'embedding' && w.status === 'processing'
    );
  }

  // Check if indexing workers are actively processing
  isIndexingActive(upload: IngestProgressUpdate): boolean {
    if (!upload.stats?.workerStatuses) return false;
    return upload.stats.workerStatuses.some(
      w => w.workerType === 'indexing' && w.status === 'processing'
    );
  }

  // Check if chunking workers are actively processing
  isChunkingActive(upload: IngestProgressUpdate): boolean {
    if (!upload.stats?.workerStatuses) return false;
    return upload.stats.workerStatuses.some(
      w => w.workerType === 'chunking' && w.status === 'processing'
    );
  }

  // Check if there are items waiting in the embedding queue (chunks to embed)
  hasEmbeddingQueueItems(upload: IngestProgressUpdate): boolean {
    return (upload.stats?.queueStatus?.chunkQueueSize || 0) > 0;
  }

  // Check if there are items waiting in the indexing queue (embeddings to index)
  hasIndexingQueueItems(upload: IngestProgressUpdate): boolean {
    return (upload.stats?.queueStatus?.embeddedQueueSize || 0) > 0;
  }

  formatTime(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    const minutes = Math.floor(ms / 60000);
    const seconds = Math.floor((ms % 60000) / 1000);
    return `${minutes}m ${seconds}s`;
  }

  formatBytes(bytes: number | undefined | null): string {
    if (bytes === null || bytes === undefined) return 'N/A';
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  calculateStagePercent(completed: number | undefined, total: number | undefined): number {
    if (!total || total === 0) return 0;
    return Math.min(100, Math.round(((completed || 0) / total) * 100));
  }

  /**
   * Get the total chunks for progress calculation.
   * For vector population jobs, use documentsLoaded (total chunks from Lucene source).
   * For document ingest jobs, use chunksCreated.
   */
  getTotalChunks(upload: IngestProgressUpdate): number {
    if (!upload.stats) return 0;

    // For vector population, documentsLoaded represents total source chunks from Lucene
    if (this.isVectorPopulationTask(upload) && upload.stats.documentsLoaded && upload.stats.documentsLoaded > 0) {
      return upload.stats.documentsLoaded;
    }

    // For document ingest, chunksCreated is the total
    return upload.stats.chunksCreated || 0;
  }

  /**
   * Format a number for display with K/M suffixes for large values.
   */
  formatNumber(value: number | undefined | null): string {
    if (value === undefined || value === null) return '0';
    if (value >= 1000000) {
      return (value / 1000000).toFixed(1) + 'M';
    } else if (value >= 1000) {
      return (value / 1000).toFixed(1) + 'K';
    }
    return value.toString();
  }

  /**
   * Calculate remaining chunks to process.
   * Uses chunksEmbedded + inProgress as the base for what's been handled.
   * When on final batch, caps remaining at 0 to avoid display issues.
   */
  getRemainingChunks(upload: IngestProgressUpdate): number {
    if (!upload.stats) return 0;

    const total = this.getTotalChunks(upload);
    const embedded = upload.stats.chunksEmbedded || 0;
    const inProgress = this.getEmbeddingInProgress(upload);

    // Check if we're on the final batch - if so, remaining should be 0 or close to 0
    const batch = upload.stats.currentEmbeddingBatch;
    if (batch && batch.batchNumber && batch.totalBatches && batch.batchNumber >= batch.totalBatches) {
      // On final batch - remaining is just what's in progress, not queued
      return Math.max(0, inProgress);
    }

    // Normal calculation: total - embedded - inProgress
    // But cap at 0 if we've embedded more than expected (can happen with count mismatches)
    const remaining = total - embedded - inProgress;
    return Math.max(0, remaining);
  }

  calculateETA(upload: IngestProgressUpdate): string | null {
    if (!upload.stats || !upload.stats.chunksPerSecond || upload.stats.chunksPerSecond <= 0) return null;

    const totalChunks = this.getTotalChunks(upload);
    const completed = this.isVectorPopulationTask(upload)
      ? (upload.stats.chunksEmbedded || 0)
      : (upload.stats.chunksIndexed || 0);
    const remaining = totalChunks - completed;

    if (remaining <= 0) return null;

    const secondsRemaining = Math.ceil(remaining / upload.stats.chunksPerSecond);
    if (secondsRemaining < 60) return `${secondsRemaining}s`;
    if (secondsRemaining < 3600) {
      const mins = Math.floor(secondsRemaining / 60);
      const secs = secondsRemaining % 60;
      return `${mins}m ${secs}s`;
    }
    const hours = Math.floor(secondsRemaining / 3600);
    const mins = Math.floor((secondsRemaining % 3600) / 60);
    return `${hours}h ${mins}m`;
  }

  onTabChange(index: number): void {
    this.selectedTabIndex = index;
  }

  triggerFileInput(): void {
    if (this.fileInput) {
      this.fileInput.nativeElement.click();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ADD FACT DIALOG
  // ═══════════════════════════════════════════════════════════════════════════════

  openAddFactDialog(): void {
    if (!this.activeSheet) {
      this.showError('Please select a fact sheet first');
      return;
    }

    const dialogData: AddSourceDialogData = {
      availableLoaders: this.availableLoaders,
      availableChunkers: this.availableChunkers
    };

    const dialogRef = this.dialog.open(AddSourceDialogComponent, {
      width: '700px',
      maxWidth: '95vw',
      maxHeight: '90vh',
      data: dialogData,
      panelClass: 'add-source-dialog'
    });

    dialogRef.afterClosed().subscribe((result: AddSourceDialogResult | undefined) => {
      if (result) {
        this.processAddSourceResult(result);
      }
    });
  }

  private processAddSourceResult(result: AddSourceDialogResult): void {
    // Handle file uploads
    if (result.files && result.files.length > 0) {
      this.uploadFilesWithOptions(result.files, result);
    } else if (result.file) {
      this.uploadFilesWithOptions([result.file], result);
    }
    // Handle URL source
    else if (result.url) {
      this.addUrlSource(result);
    }
    // Handle text source
    else if (result.sourceType === 'text' && result.textContent) {
      this.addTextSource(result);
    }
    // Handle YouTube source
    else if (result.sourceType === 'youtube' && result.youtubeUrl) {
      this.addYouTubeSource(result);
    }
    // Handle Confluence source
    else if (result.sourceType === 'confluence') {
      this.addConfluenceSource(result);
    }
    // Handle Slack source
    else if (result.sourceType === 'slack' || result.sourceType === 'slack_history') {
      this.addSlackSource(result);
    }
  }

  private uploadFilesWithOptions(files: File[], options: AddSourceDialogResult): void {
    // Use async upload - progress tracked via WebSocket
    this.documentService.uploadFilesAsync(files, options.selectedLoader, options.chunkerName, options.processingMode, options.subprocessConfig).subscribe({
      next: (response: BatchAsyncUploadResponse) => {
        const accepted = response.acceptedCount;
        const rejected = response.rejectedCount;
        if (accepted > 0) {
          this.showSuccess(`${accepted} file(s) queued for processing to "${this.activeSheet?.name}". ${rejected > 0 ? rejected + ' rejected.' : ''} Track progress below.`);

          // Immediately add accepted tasks to UI so they show up right away
          response.files.filter(f => f.accepted && f.taskId).forEach(file => {
            const initialUpdate: IngestProgressUpdate = {
              taskId: file.taskId!,
              fileName: file.fileName,
              phase: IngestPhase.QUEUED,
              status: IngestStatus.PENDING,
              progressPercent: 0,
              currentStep: 'Queued',
              message: 'Waiting to start processing...',
              stats: null,
              errorMessage: null,
              timestamp: new Date().toISOString(),
              factSheetId: this.activeSheet?.id || null
            };
            this.activeUploads.set(file.taskId!, initialUpdate);
            // Auto-expand panels for new tasks
            this.expandedSubprocessLogs.add(file.taskId!);
            this.expandedJobInfo.add(file.taskId!);
          });
          this.updateFilteredUploadsArray();

          // Immediately fetch actual status from backend to catch any progress
          // that may have been sent via WebSocket before we subscribed
          setTimeout(() => this.loadActiveIngestTasks(), 100);
        } else {
          this.showError(`All files rejected: ${response.message}`);
        }
      },
      error: (err) => {
        this.showError(`Failed to upload files: ${err.message || 'Unknown error'}`);
      }
    });
  }

  private addUrlSource(result: AddSourceDialogResult): void {
    if (!result.url) return;

    // URL processing - let backend handle async progress tracking
    this.documentService.addUrlSource(result.url, {
      fileName: result.fileName,
      loaderName: result.selectedLoader,
      chunkerName: result.chunkerName,
      rebuildIndex: result.rebuildIndex
    }).subscribe({
      next: (response: any) => {
        if (response?.taskId) {
          this.showSuccess(`URL queued for processing to "${this.activeSheet?.name}". Track progress below.`);
        } else {
          this.showSuccess(`URL added to "${this.activeSheet?.name}"`);
          this.loadFacts();
          this.factSheetService.loadSheets().subscribe();
        }
      },
      error: (err) => {
        this.showError(`Failed to add URL: ${err.message || 'Unknown error'}`);
      }
    });
  }

  private addTextSource(result: AddSourceDialogResult): void {
    if (!result.textContent) return;

    // Text processing with async tracking
    this.documentService.addTextSource(result.textContent, {
      sourceName: result.textSourceName,
      chunkerName: result.chunkerName,
      rebuildIndex: result.rebuildIndex
    }).subscribe({
      next: (response: any) => {
        if (response?.taskId) {
          this.showSuccess(`Text content queued for processing to "${this.activeSheet?.name}". Track progress below.`);
        } else {
          this.showSuccess(`Text added to "${this.activeSheet?.name}"`);
          this.loadFacts();
          this.factSheetService.loadSheets().subscribe();
        }
      },
      error: (err) => {
        this.showError(`Failed to add text: ${err.message || 'Unknown error'}`);
      }
    });
  }

  private addYouTubeSource(result: AddSourceDialogResult): void {
    if (!result.youtubeUrl) return;

    // YouTube transcript with async tracking
    this.documentService.addYouTubeSource(result.youtubeUrl, {
      language: result.youtubeLanguage,
      saveTranscript: result.saveTranscriptFile,
      chunkerName: result.chunkerName,
      rebuildIndex: result.rebuildIndex
    }).subscribe({
      next: (response: YouTubeTranscriptResponse) => {
        if (response.processingStarted && response.taskId) {
          this.showSuccess(`YouTube transcript for "${response.videoTitle}" queued. Track progress below.`);
        } else {
          this.showSuccess(`YouTube transcript added to "${this.activeSheet?.name}"`);
          this.loadFacts();
          this.factSheetService.loadSheets().subscribe();
        }
      },
      error: (err) => {
        this.showError(`Failed to add YouTube source: ${err.message || 'Unknown error'}`);
      }
    });
  }

  private addConfluenceSource(result: AddSourceDialogResult): void {
    // First connect to Confluence, then ingest
    const connectionConfig = {
      baseUrl: result.confluenceBaseUrl!,
      email: result.confluenceEmail || '',
      apiToken: result.confluenceApiToken || ''
    };

    this.confluenceService.connect(connectionConfig).subscribe({
      next: (status) => {
        if (status.connected) {
          const ingestRequest: ConfluenceIngestRequest = {
            pageIds: [],
            spaceKeys: [result.confluenceSpaceKey!],
            includeChildren: result.confluenceIncludeChildren !== false,
            includeAttachments: result.confluenceIncludeAttachments === true,
            chunkerName: result.chunkerName,
            processingMode: result.processingMode || 'auto'
          };

          this.confluenceService.ingestSpace(result.confluenceSpaceKey!, ingestRequest).subscribe({
            next: (response) => {
              if (response.pagesQueued > 0) {
                this.showSuccess(`Confluence space "${result.confluenceSpaceKey}" queued. ${response.pagesQueued} pages will be processed. Track progress below.`);
              } else {
                this.showError(`No pages found in Confluence space: ${response.message}`);
              }
            },
            error: (err) => {
              this.showError(`Failed to ingest Confluence: ${err.message || 'Unknown error'}`);
            }
          });
        } else {
          this.showError('Failed to connect to Confluence');
        }
      },
      error: (err) => {
        this.showError(`Failed to connect to Confluence: ${err.message || 'Unknown error'}`);
      }
    });
  }

  private addSlackSource(result: AddSourceDialogResult): void {
    // Slack messages with async tracking
    this.documentService.addSlackSource({
      channelId: result.slackChannelId!,
      token: result.slackToken,
      messageLimit: result.slackMessageLimit,
      includeThreads: result.slackIncludeThreads,
      startDate: result.slackStartDate,
      endDate: result.slackEndDate,
      daysBack: result.slackDaysBack,
      loadAllChannels: result.slackLoadAllChannels,
      historyMode: result.slackHistoryMode,
      chunkerName: result.chunkerName,
      rebuildIndex: result.rebuildIndex
    }).subscribe({
      next: (response: any) => {
        if (response?.taskId) {
          this.showSuccess(`Slack messages queued for processing. Track progress below.`);
        } else {
          this.showSuccess(`Slack messages added to "${this.activeSheet?.name}"`);
          this.loadFacts();
          this.factSheetService.loadSheets().subscribe();
        }
      },
      error: (err) => {
        this.showError(`Failed to add Slack source: ${err.message || 'Unknown error'}`);
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FACT OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) return;

    const files = Array.from(input.files);
    this.uploadFiles(files);
    input.value = ''; // Reset input
  }

  uploadFiles(files: File[]): void {
    if (!this.activeSheet) {
      this.showError('Please select a fact sheet first');
      return;
    }

    // Use async batch upload - progress tracked via WebSocket
    this.documentService.uploadFilesAsync(files).subscribe({
      next: (response: BatchAsyncUploadResponse) => {
        const accepted = response.acceptedCount;
        const rejected = response.rejectedCount;
        if (accepted > 0) {
          this.showSuccess(`${accepted} file(s) queued for processing. ${rejected > 0 ? rejected + ' rejected.' : ''} Track progress in the panel above.`);

          // Immediately add accepted tasks to UI so they show up right away
          response.files.filter(f => f.accepted && f.taskId).forEach(file => {
            const initialUpdate: IngestProgressUpdate = {
              taskId: file.taskId!,
              fileName: file.fileName,
              phase: IngestPhase.QUEUED,
              status: IngestStatus.PENDING,
              progressPercent: 0,
              currentStep: 'Queued',
              message: 'Waiting to start processing...',
              stats: null,
              errorMessage: null,
              timestamp: new Date().toISOString(),
              factSheetId: this.activeSheet?.id || null
            };
            this.activeUploads.set(file.taskId!, initialUpdate);
            // Auto-expand panels for new tasks
            this.expandedSubprocessLogs.add(file.taskId!);
            this.expandedJobInfo.add(file.taskId!);
          });
          this.updateFilteredUploadsArray();
        } else {
          this.showError(`All files rejected: ${response.message}`);
        }
      },
      error: (err) => {
        this.showError(`Failed to upload files: ${err.message || 'Unknown error'}`);
      }
    });
  }

  selectFact(fact: Fact): void {
    this.selectedFact = this.selectedFact?.id === fact.id ? null : fact;
  }

  deleteFact(fact: Fact, event: Event): void {
    event.stopPropagation();

    const dialogData: ConfirmDialogData = {
      title: 'Delete Fact',
      message: `Delete "${fact.fileName}"?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.factSheetService.deleteFact(fact.id).subscribe({
          next: () => {
            if (this.selectedFact?.id === fact.id) {
              this.selectedFact = null;
            }
            this.showSuccess(`Deleted "${fact.fileName}"`);
          },
          error: () => this.showError('Failed to delete fact')
        });
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SHEET OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  selectSheet(sheet: FactSheet): void {
    if (sheet.isActive) return;

    console.log(`[FactSheetManager] selectSheet called: switching from ${this.activeSheet?.id} to ${sheet.id}`);
    this.isLoading = true;
    this.factSheetService.activateSheet(sheet.id).subscribe({
      next: (activated) => {
        console.log(`[FactSheetManager] Sheet activation complete: ${activated.id} (${activated.name})`);
        this.isLoading = false;
        this.showSuccess(`Switched to "${activated.name}"`);
        // Force immediate update of filtered uploads after sheet switch
        this.updateFilteredUploadsArray();
      },
      error: () => {
        this.isLoading = false;
        this.showError('Failed to switch fact sheet');
      }
    });
  }

  openCreateForm(): void {
    this.sheetForm.reset({
      name: '',
      description: '',
      color: '#1976d2',
      icon: 'folder'
    });
    this.showCreateForm = true;
    this.showEditForm = false;
  }

  cancelCreate(): void {
    this.showCreateForm = false;
    this.sheetForm.reset();
  }

  createSheet(): void {
    if (this.sheetForm.invalid) return;

    // Start with basic form values
    const request: CreateFactSheetRequest = {
      name: this.sheetForm.value.name,
      description: this.sheetForm.value.description || undefined,
      color: this.sheetForm.value.color,
      icon: this.sheetForm.value.icon
    };

    // Copy model source configuration from the active sheet (if any)
    // This ensures new fact sheets inherit the archive/registry configuration
    if (this.activeSheet) {
      // Copy embedding model configuration
      if (this.activeSheet.embeddingModelSource) {
        request.embeddingModelSource = this.activeSheet.embeddingModelSource;
      }
      if (this.activeSheet.embeddingArchiveId) {
        request.embeddingArchiveId = this.activeSheet.embeddingArchiveId;
      }
      if (this.activeSheet.embeddingModel) {
        request.embeddingModel = this.activeSheet.embeddingModel;
      }

      // Copy cross-encoder/reranking configuration
      if (this.activeSheet.crossEncoderModelSource) {
        request.crossEncoderModelSource = this.activeSheet.crossEncoderModelSource;
      }
      if (this.activeSheet.crossEncoderArchiveId) {
        request.crossEncoderArchiveId = this.activeSheet.crossEncoderArchiveId;
      }
      // Note: We intentionally don't copy crossEncoderModel, rerankingEnabled, rerankerType, etc.
      // since those are more specific configuration choices that may not apply to all fact sheets.
      // We only copy the source (archive vs staging) so the new sheet uses the same model source.
    }

    this.isLoading = true;
    this.factSheetService.createSheet(request).subscribe({
      next: (sheet) => {
        this.isLoading = false;
        this.showCreateForm = false;
        this.sheetForm.reset();
        // Activate the new sheet
        this.factSheetService.activateSheet(sheet.id).subscribe();
        this.showSuccess(`Created fact sheet "${sheet.name}"`);
      },
      error: () => {
        this.isLoading = false;
        this.showError('Failed to create fact sheet');
      }
    });
  }

  openEditForm(sheet: FactSheet): void {
    this.editingSheet = sheet;
    this.sheetForm.setValue({
      name: sheet.name,
      description: sheet.description || '',
      color: sheet.color,
      icon: sheet.icon
    });
    this.showEditForm = true;
    this.showCreateForm = false;
  }

  cancelEdit(): void {
    this.showEditForm = false;
    this.editingSheet = null;
    this.sheetForm.reset();
  }

  updateSheet(): void {
    if (this.sheetForm.invalid || !this.editingSheet) return;

    const request: UpdateFactSheetRequest = {
      name: this.sheetForm.value.name,
      description: this.sheetForm.value.description || undefined,
      color: this.sheetForm.value.color,
      icon: this.sheetForm.value.icon
    };

    this.isLoading = true;
    this.factSheetService.updateSheet(this.editingSheet.id, request).subscribe({
      next: (sheet) => {
        this.isLoading = false;
        this.showEditForm = false;
        this.editingSheet = null;
        this.sheetForm.reset();
        this.showSuccess(`Updated "${sheet.name}"`);
      },
      error: () => {
        this.isLoading = false;
        this.showError('Failed to update fact sheet');
      }
    });
  }

  deleteSheet(sheet: FactSheet): void {
    if (sheet.name === 'Default') {
      this.showError('Cannot delete the default fact sheet');
      return;
    }

    const dialogData: ConfirmDialogData = {
      title: 'Delete Fact Sheet',
      message: `Delete "${sheet.name}" and all ${sheet.factCount} facts?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'folder_delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.isLoading = true;
        this.factSheetService.deleteSheet(sheet.id).subscribe({
          next: () => {
            this.isLoading = false;
            this.showSuccess(`Deleted "${sheet.name}"`);
          },
          error: () => {
            this.isLoading = false;
            this.showError('Failed to delete fact sheet');
          }
        });
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // HELPERS
  // ═══════════════════════════════════════════════════════════════════════════════

  formatSize(bytes: number | null): string {
    if (!bytes) return '';
    return formatFileSize(bytes);
  }

  getFileIcon(fact: Fact): string {
    const ext = fact.extension?.toLowerCase() || '';
    const iconMap: { [key: string]: string } = {
      'pdf': 'picture_as_pdf',
      'doc': 'description',
      'docx': 'description',
      'txt': 'article',
      'md': 'article',
      'html': 'code',
      'json': 'data_object',
      'csv': 'table_chart',
      'xls': 'table_chart',
      'xlsx': 'table_chart',
      'ppt': 'slideshow',
      'pptx': 'slideshow',
      'png': 'image',
      'jpg': 'image',
      'jpeg': 'image',
      'gif': 'image'
    };
    return iconMap[ext] || 'insert_drive_file';
  }

  getFileIconClass(fact: Fact): string {
    const ext = fact.extension?.toLowerCase() || '';
    const classMap: { [key: string]: string } = {
      'pdf': 'icon-pdf',
      'doc': 'icon-doc',
      'docx': 'icon-doc',
      'txt': 'icon-txt',
      'md': 'icon-txt',
      'html': 'icon-code',
      'json': 'icon-code',
      'csv': 'icon-spreadsheet',
      'xls': 'icon-spreadsheet',
      'xlsx': 'icon-spreadsheet'
    };
    return classMap[ext] || 'icon-default';
  }

  getSourceTypeLabel(type: string): string {
    const labels: { [key: string]: string } = {
      'UPLOAD': 'Uploaded',
      'URL': 'From URL',
      'TEXT': 'Text',
      'STORED': 'Stored',
      'IMPORT': 'Imported'
    };
    return labels[type] || type;
  }

  private showSuccess(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 3000,
      panelClass: ['snackbar-success']
    });
  }

  private showError(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 5000,
      panelClass: ['snackbar-error']
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // INDEX BROWSER METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  loadIndexerStatus(): void {
    this.statusLoading = true;
    this.indexBrowserService.getIndexBrowserStatus().subscribe({
      next: (status: any) => {
        this.indexerStatus = status;
        this.statusLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.statusLoading = false;
        this.cdr.markForCheck();
      }
    });
  }

  private restoreActiveVectorPopulationState(): void {
    this.indexBrowserService.getActiveTrackedVectorPopulationTasks().subscribe({
      next: (response) => {
        if (!response.available || response.activeCount === 0 || !response.tasks?.length) {
          return;
        }

        let hasActive = false;

        response.tasks.forEach(task => {
          const progress = this.mapTrackedVectorPopulationToProgress(task);
          const unifiedUpdate = this.convertVectorPopulationToIngestProgress(progress);
          const existing = this.activeUploads.get(task.taskId);
          const merged = this.mergeProgressUpdate(existing, unifiedUpdate);
          this.activeUploads.set(task.taskId, merged);
          this.ensureVectorPopulationEnvironment(task.taskId);

          if (!this.isVectorPopulationTerminalPhase(progress.phase)) {
            hasActive = true;
            this.currentVectorTaskId = task.taskId;
            this.vectorPopulationProgress = progress;
          }
        });

        this.isVectorPopulating = hasActive;
        this.updateFilteredUploadsArray();
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.warn('Could not restore vector population tasks:', err);
      }
    });
  }

  private mapTrackedVectorPopulationToProgress(task: VectorPopulationUpdate): VectorPopulationProgress {
    return {
      taskId: task.taskId,
      phase: String(task.phase) || 'LOADING',
      progressPercent: task.progressPercent || 0,
      message: task.message || '',
      currentStep: task.currentStep,
      keywordIndexPath: task.keywordIndexPath,
      vectorIndexPath: task.vectorIndexPath,
      stats: task.stats ? {
        documentsLoaded: task.stats.documentsLoaded || 0,
        documentsProcessed: task.stats.documentsLoaded || 0,
        chunksCreated: task.stats.chunksCreated || 0,
        chunksEmbedded: task.stats.chunksEmbedded || 0,
        chunksIndexed: task.stats.chunksIndexed || 0,
        totalDocuments: task.stats.totalDocuments || 0,
        throughputDocsPerSec: task.stats.throughputDocsPerSec || 0,
        elapsedTimeMs: task.stats.elapsedTimeMs || 0,
        actualKeywordIndexCount: task.stats.actualKeywordIndexCount,
        actualVectorStoreCount: task.stats.actualVectorStoreCount,
        workerStatuses: task.stats.workerStatuses,
        queueStatus: task.stats.queueStatus,
        currentEmbeddingBatch: task.stats.currentEmbeddingBatch,
        batchHistory: task.stats.batchHistory  // Forward batch history for UI display
      } : undefined
    };
  }

  private isVectorPopulationTerminalPhase(phase: string): boolean {
    return ['COMPLETED', 'FAILED', 'CANCELLED'].includes(String(phase).toUpperCase());
  }

  /**
   * Start vector population from the keyword index.
   * This will create vector embeddings for all documents in the keyword index.
   */
  startVectorPopulation(): void {
    if (this.isVectorPopulating) {
      return;
    }

    this.isVectorPopulating = true;
    this.snackBar.open('Starting vector population from keyword index...', 'Close', { duration: 3000 });

    // Subscribe to WebSocket progress first
    this.subscribeToVectorProgress();

    this.indexBrowserService.startVectorPopulation().subscribe({
      next: (response: any) => {
        this.currentVectorTaskId = response.taskId;

        // Initialize progress display
        this.vectorPopulationProgress = {
          taskId: response.taskId,
          phase: 'LOADING',
          progressPercent: 0,
          message: 'Starting vector population...'
        };

        this.snackBar.open(
          response.message || 'Vector population started. Watch progress below.',
          'Close',
          { duration: 5000, panelClass: ['snackbar-success'] }
        );
        this.mainPanelNavigationService.requestMainPanelFocus();
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.isVectorPopulating = false;
        this.snackBar.open(
          `Error starting vector population: ${err.message || 'Server error'}`,
          'Close',
          { duration: 5000, panelClass: ['snackbar-error'] }
        );
        this.cdr.markForCheck();
      }
    });
  }

  /**
   * Cancel the current vector population task.
   */
  cancelVectorPopulation(): void {
    if (!this.isVectorPopulating || !this.currentVectorTaskId) {
      return;
    }

    this.indexBrowserService.cancelVectorPopulation(this.currentVectorTaskId).subscribe({
      next: () => {
        this.snackBar.open('Cancellation requested...', 'Close', { duration: 3000 });
      },
      error: (err) => {
        this.snackBar.open(`Error cancelling: ${err.message}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  /**
   * Toggle the vector population logs panel visibility.
   */
  toggleVectorPopulationLogs(): void {
    this.isVectorPopulationLogsExpanded = !this.isVectorPopulationLogsExpanded;
  }

  /**
   * Toggle the embedding subprocess logs panel visibility.
   */
  toggleEmbeddingLogs(): void {
    this.showEmbeddingLogs = !this.showEmbeddingLogs;
  }

  /**
   * Subscribe to vector population progress updates via WebSocket.
   */
  private subscribeToVectorProgress(): void {
    this.unsubscribeFromVectorProgress();

    console.log('[VECTOR-PROGRESS] Subscribing to vector population progress');
    this.webSocketService.connect();

    this.wsVectorProgressSub = this.webSocketService
      .subscribeToVectorPopulation<VectorPopulationProgress>()
      .subscribe({
        next: (progress: VectorPopulationProgress) => {
          console.log('[VECTOR-PROGRESS] Received:', progress.taskId, progress.phase, progress.progressPercent + '%');

          const isTerminal = this.isVectorPopulationTerminalPhase(progress.phase);
          if (!this.currentVectorTaskId || (!isTerminal && this.currentVectorTaskId !== progress.taskId)) {
            this.currentVectorTaskId = progress.taskId;
          }

          this.vectorPopulationProgress = progress;
          if (!isTerminal) {
            this.isVectorPopulating = true;
          } else if (this.currentVectorTaskId === progress.taskId) {
            this.isVectorPopulating = false;
          }

          // Convert to IngestProgressUpdate and add to activeUploads for unified display
          const unifiedUpdate = this.convertVectorPopulationToIngestProgress(progress);
          const existing = this.activeUploads.get(progress.taskId);
          const merged = this.mergeProgressUpdate(existing, unifiedUpdate);
          this.activeUploads.set(progress.taskId, merged);
          this.ensureVectorPopulationEnvironment(progress.taskId);
          this.updateFilteredUploadsArray();

          this.cdr.detectChanges();

          // Handle completion/failure
          if (progress.phase === 'COMPLETED') {
            this.handleVectorPopulationComplete(progress);
          } else if (progress.phase === 'FAILED') {
            this.handleVectorPopulationFailed(progress);
          } else if (progress.phase === 'CANCELLED') {
            this.handleVectorPopulationCancelled();
          }
        },
        error: (err: Error) => {
          console.error('WebSocket vector progress error:', err);
        }
      });
  }

  /**
   * Convert VectorPopulationProgress to IngestProgressUpdate for unified job tracking.
   */
  private convertVectorPopulationToIngestProgress(progress: VectorPopulationProgress): IngestProgressUpdate {
    // Map vector population phases to IngestPhase
    let phase: IngestPhase;
    let status: IngestStatus;
    switch (progress.phase) {
      case 'LOADING':
        phase = IngestPhase.LOADING;
        status = IngestStatus.IN_PROGRESS;
        break;
      case 'EMBEDDING':
        phase = IngestPhase.EMBEDDING;
        status = IngestStatus.IN_PROGRESS;
        break;
      case 'INDEXING':
        phase = IngestPhase.INDEXING;
        status = IngestStatus.IN_PROGRESS;
        break;
      case 'COMPLETED':
        phase = IngestPhase.COMPLETED;
        status = IngestStatus.COMPLETED;
        break;
      case 'FAILED':
        phase = IngestPhase.FAILED;
        status = IngestStatus.FAILED;
        break;
      case 'CANCELLED':
        phase = IngestPhase.COMPLETED; // No CANCELLED phase in IngestPhase
        status = IngestStatus.CANCELLED;
        break;
      default:
        phase = IngestPhase.QUEUED;
        status = IngestStatus.IN_PROGRESS;
    }

    return {
      taskId: progress.taskId,
      fileName: 'Vector Population',
      phase: phase,
      status: status,
      progressPercent: progress.progressPercent,
      currentStep: progress.currentStep || progress.phase,
      message: progress.message,
      stats: progress.stats ? {
        documentsLoaded: progress.stats.documentsLoaded || progress.stats.totalDocuments,
        chunksCreated: progress.stats.chunksCreated,
        chunksEmbedded: progress.stats.chunksEmbedded,
        chunksIndexed: progress.stats.chunksIndexed,
        documentsIndexed: progress.stats.documentsProcessed,
        totalProcessingTimeMs: progress.stats.elapsedTimeMs,
        loaderUsed: null,
        chunkerUsed: null,
        processedDocumentIds: [],
        chunksPerSecond: progress.stats.throughputDocsPerSec,
        currentEmbeddingBatch: progress.stats.currentEmbeddingBatch,
        batchHistory: progress.stats.batchHistory,  // Forward batch history for UI display
        subprocessRuntimeInfo: progress.stats.runtimeInfo,
        // Restart tracking fields
        restartAttempt: progress.stats.restartAttempt,
        maxRestartAttempts: progress.stats.maxRestartAttempts,
        heapSize: progress.stats.heapSize,
        heapIncreased: progress.stats.heapIncreased
      } : null,
      errorMessage: null,
      timestamp: new Date().toISOString(),
      factSheetId: this.activeSheet?.id || null,
      jobType: 'VECTOR_POPULATION',
      keywordIndexPath: progress.keywordIndexPath,
      vectorIndexPath: progress.vectorIndexPath
    };
  }

  private ensureVectorPopulationEnvironment(taskId: string): void {
    const existing = this.activeUploads.get(taskId);
    if (existing?.stats?.subprocessRuntimeInfo?.nd4jEnvironmentUsed) {
      return;
    }

    if (this.vectorPopulationEnvironmentRequests.has(taskId)) {
      return;
    }
    this.vectorPopulationEnvironmentRequests.add(taskId);

    this.indexBrowserService.getVectorPopulationTaskEnvironment(taskId).subscribe({
      next: (envResponse) => {
        if (!envResponse?.environmentCaptured || !envResponse.nd4jEnvironment) {
          this.vectorPopulationEnvironmentRequests.delete(taskId);
          return;
        }

        const latest = this.activeUploads.get(taskId);
        if (!latest) {
          return;
        }

        const baseStats: IngestStats = latest.stats ?? {
          documentsLoaded: 0,
          chunksCreated: 0,
          chunksEmbedded: 0,
          chunksIndexed: 0,
          documentsIndexed: 0,
          totalProcessingTimeMs: 0,
          loaderUsed: null,
          chunkerUsed: null,
          processedDocumentIds: []
        };

        const runtimeInfo: SubprocessRuntimeInfo = {
          ...(baseStats.subprocessRuntimeInfo || {}),
          processMode: baseStats.subprocessRuntimeInfo?.processMode || 'SUBPROCESS',
          nd4jEnvironmentUsed: envResponse.nd4jEnvironment
        };

        const updatedStats: IngestStats = {
          ...baseStats,
          subprocessRuntimeInfo: runtimeInfo
        };

        this.activeUploads.set(taskId, { ...latest, stats: updatedStats });
        this.updateFilteredUploadsArray();
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.warn('Failed to fetch vector population environment:', err);
        this.vectorPopulationEnvironmentRequests.delete(taskId);
      }
    });
  }

  /**
   * Unsubscribe from vector population progress updates.
   */
  private unsubscribeFromVectorProgress(): void {
    if (this.wsVectorProgressSub) {
      this.wsVectorProgressSub.unsubscribe();
      this.wsVectorProgressSub = null;
    }
  }

  /**
   * Handle vector population completion.
   */
  private handleVectorPopulationComplete(progress: VectorPopulationProgress): void {
    this.isVectorPopulating = false;

    const stats = progress.stats;
    const throughput = stats?.throughputDocsPerSec?.toFixed(1) || '0';
    const indexed = stats?.chunksIndexed || 0;

    this.snackBar.open(
      `Vector population complete! ${indexed} documents indexed (${throughput} docs/sec)`,
      'Close',
      { duration: 8000, panelClass: ['snackbar-success'] }
    );

    // Record which embedding model was used for indexing
    if (this.activeSheet) {
      // Get the effective model used (sheet override or system default)
      const modelUsed = this.activeSheet.embeddingModel || this.getSystemEncoderModelId();
      if (modelUsed) {
        this.factSheetService.setIndexedWithModel(this.activeSheet.id, modelUsed).subscribe({
          next: () => {
            console.log(`Recorded indexedWithModel='${modelUsed}' for sheet '${this.activeSheet?.name}'`);
          },
          error: (err) => {
            console.warn('Failed to record indexed model:', err);
          }
        });
      }
    }

    // Refresh the index status
    this.loadIndexerStatus();
    this.cdr.markForCheck();
  }

  /**
   * Trigger a reindex of the vector store (used when model mismatch detected).
   */
  triggerReindex(): void {
    if (!this.activeSheet || this.isVectorPopulating) {
      return;
    }

    const sheetName = this.activeSheet.name;
    const indexedCount = this.activeSheet.indexedCount || 0;
    const configuredModel = this.activeSheet.embeddingModel || 'default';
    const indexedWithModel = this.activeSheet.indexedWithModel || 'unknown';

    const dialogData: ConfirmDialogData = {
      title: 'Rebuild Vector Store',
      message: `This will rebuild the vector store for "${sheetName}" using the ${configuredModel} embedding model.\n\nCurrent index was built with: ${indexedWithModel}\nDocuments to reindex: ${indexedCount}\n\nThis may take some time depending on the number of documents. Continue?`,
      confirmText: 'Rebuild',
      confirmColor: 'primary',
      icon: 'sync'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.snackBar.open('Starting vector store rebuild...', 'Close', { duration: 3000 });
        this.startVectorPopulation();
      });
  }

  /**
   * Get the system encoder model ID (for recording when indexing).
   */
  private getSystemEncoderModelId(): string | null {
    if (this.systemConfig?.embeddingModel?.modelId) {
      return this.systemConfig.embeddingModel.modelId;
    }
    return null;
  }

  /**
   * Handle vector population failure.
   */
  private handleVectorPopulationFailed(progress: VectorPopulationProgress): void {
    this.isVectorPopulating = false;

    // Keep progress visible with FAILED state so UI shows error
    this.vectorPopulationProgress = {
      ...progress,
      phase: 'FAILED',
      progressPercent: 0  // Reset progress to show failed state clearly
    };

    // Show error snackbar with longer duration for native crashes
    const isNativeCrash = progress.message?.includes('Native crash') ||
      progress.message?.includes('native crash');
    const duration = isNativeCrash ? 0 : 10000;  // 0 = stays until dismissed

    this.snackBar.open(
      `Vector population failed: ${progress.message}`,
      'Dismiss',
      {
        duration: duration,
        panelClass: ['snackbar-error']
      }
    );

    // Clear progress after a delay to let user see the error state
    setTimeout(() => {
      if (this.vectorPopulationProgress?.phase === 'FAILED') {
        this.vectorPopulationProgress = null;
        this.cdr.markForCheck();
      }
    }, isNativeCrash ? 30000 : 10000);  // Keep visible longer for native crashes

    this.loadIndexerStatus();
    this.cdr.markForCheck();
  }

  /**
   * Handle vector population cancellation.
   */
  private handleVectorPopulationCancelled(): void {
    this.isVectorPopulating = false;
    this.vectorPopulationProgress = null;

    this.snackBar.open('Vector population cancelled.', 'Close', { duration: 5000 });
    this.loadIndexerStatus();
    this.cdr.markForCheck();
  }

  /**
   * Get the display name for a vector population phase.
   */
  getVectorPhaseDisplayName(phase: string): string {
    const phaseNames: { [key: string]: string } = {
      'LOADING': 'Loading Documents',
      'EMBEDDING': 'Generating Embeddings',
      'INDEXING': 'Indexing Vectors',
      'COMPLETED': 'Completed',
      'FAILED': 'Failed',
      'CANCELLED': 'Cancelled'
    };
    return phaseNames[phase] || phase;
  }

  /**
   * Get the icon for a vector population phase.
   */
  getVectorPhaseIcon(phase: string): string {
    const phaseIcons: { [key: string]: string } = {
      'LOADING': 'description',
      'EMBEDDING': 'memory',
      'INDEXING': 'storage',
      'COMPLETED': 'check_circle',
      'FAILED': 'error',
      'CANCELLED': 'cancel'
    };
    return phaseIcons[phase] || 'pending';
  }

  /**
   * Get the color for a vector population status.
   */
  getVectorProgressColor(phase: string): string {
    switch (phase) {
      case 'COMPLETED': return 'primary';
      case 'FAILED': return 'warn';
      case 'CANCELLED': return 'accent';
      default: return 'accent';
    }
  }

  /**
   * Calculate embedding progress percentage.
   */
  getVectorEmbeddingProgress(): number {
    const stats = this.vectorPopulationProgress?.stats;
    const total = stats?.totalDocuments || stats?.chunksCreated || 0;
    if (total === 0) return 0;
    return Math.round(((stats?.chunksEmbedded || 0) / total) * 100);
  }

  /**
   * Calculate indexing progress percentage.
   */
  getVectorIndexingProgress(): number {
    const stats = this.vectorPopulationProgress?.stats;
    const total = stats?.totalDocuments || stats?.chunksCreated || 0;
    if (total === 0) return 0;
    return Math.round(((stats?.chunksIndexed || 0) / total) * 100);
  }

  /**
   * Calculate ETA for vector population.
   */
  calculateVectorETA(): string | null {
    const stats = this.vectorPopulationProgress?.stats;
    if (!stats?.throughputDocsPerSec || stats.throughputDocsPerSec <= 0) return null;

    const totalDocs = stats.totalDocuments || 0;
    const indexed = stats.chunksIndexed || 0;
    const remaining = totalDocs - indexed;

    if (remaining <= 0) return null;

    const secondsRemaining = Math.ceil(remaining / stats.throughputDocsPerSec);

    if (secondsRemaining < 60) return `${secondsRemaining}s`;
    if (secondsRemaining < 3600) {
      const mins = Math.floor(secondsRemaining / 60);
      const secs = secondsRemaining % 60;
      return `${mins}m ${secs}s`;
    }
    const hours = Math.floor(secondsRemaining / 3600);
    const mins = Math.floor((secondsRemaining % 3600) / 60);
    return `${hours}h ${mins}m`;
  }

  /**
   * Format duration in milliseconds to human-readable string.
   */
  formatVectorDuration(ms: number | undefined | null): string {
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

  searchKeywordIndex(): void {
    if (!this.keywordSearchQuery || this.keywordSearchQuery.length < 2) {
      this.keywordDocuments = [];
      this.keywordDataSource.data = [];
      this.keywordTotalResults = 0;
      return;
    }

    this.keywordSearchLoading = true;
    this.indexBrowserService.searchIndexedDocs(
      this.keywordSearchQuery,
      this.keywordPageSize
    ).subscribe({
      next: (response: any) => {
        this.keywordDocuments = response.results || [];
        this.keywordDataSource.data = this.keywordDocuments;
        this.keywordTotalResults = response.totalResults || 0;
        this.keywordSearchLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.keywordSearchLoading = false;
        this.showError('Failed to search keyword index');
      }
    });
  }

  searchVectorStore(): void {
    if (!this.vectorSearchQuery || this.vectorSearchQuery.length < 2) {
      this.vectorDocuments = [];
      this.vectorDataSource.data = [];
      this.vectorTotalResults = 0;
      return;
    }

    this.vectorSearchLoading = true;
    this.indexBrowserService.searchVectorStore(
      this.vectorSearchQuery,
      this.vectorPageSize,
      this.similarityThreshold
    ).subscribe({
      next: (response: any) => {
        this.vectorDocuments = response.results || [];
        this.vectorDataSource.data = this.vectorDocuments;
        this.vectorTotalResults = response.totalResults || this.vectorDocuments.length;
        this.vectorSearchLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.vectorSearchLoading = false;
        this.showError('Failed to search vector store');
      }
    });
  }

  onKeywordPageChange(event: PageEvent): void {
    this.keywordCurrentPage = event.pageIndex;
    this.keywordPageSize = event.pageSize;
    this.searchKeywordIndex();
  }

  onVectorPageChange(event: PageEvent): void {
    this.vectorCurrentPage = event.pageIndex;
    this.vectorPageSize = event.pageSize;
    this.searchVectorStore();
  }

  clearKeywordSearch(): void {
    this.keywordSearchQuery = '';
    this.keywordSearchControl.setValue('');
    this.keywordDocuments = [];
    this.keywordDataSource.data = [];
    this.keywordTotalResults = 0;
    this.keywordCurrentPage = 0;
  }

  clearVectorSearch(): void {
    this.vectorSearchQuery = '';
    this.vectorSearchControl.setValue('');
    this.vectorDocuments = [];
    this.vectorDataSource.data = [];
    this.vectorTotalResults = 0;
    this.vectorCurrentPage = 0;
  }

  selectDocument(doc: any): void {
    this.selectedDocument = this.selectedDocument?.id === doc.id ? null : doc;
  }

  closeDocumentDetail(): void {
    this.selectedDocument = null;
  }

  onSimilarityThresholdChange(value: number): void {
    this.similarityThreshold = value;
    if (this.vectorSearchQuery.length >= 2) {
      this.searchVectorStore();
    }
  }

  getScoreClass(score: number): string {
    if (score >= 0.8) return 'high-score';
    if (score >= 0.5) return 'medium-score';
    return 'low-score';
  }

  truncateContent(content: string, maxLength: number = 150): string {
    if (!content) return '';
    if (content.length <= maxLength) return content;
    return content.substring(0, maxLength) + '...';
  }

  getDocPreview(doc: any): string {
    const content = doc.content || doc.text || '';
    return this.truncateContent(content, 200);
  }

  refreshIndexerStatus(): void {
    this.loadIndexerStatus();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SOURCE VIEWER METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  getSourceFileIcon(source: any): string {
    const ext = (source.extension || '').toLowerCase();
    const iconMap: { [key: string]: string } = {
      'pdf': 'picture_as_pdf',
      'doc': 'description',
      'docx': 'description',
      'txt': 'article',
      'md': 'article',
      'html': 'code',
      'json': 'data_object',
      'csv': 'table_chart',
      'xls': 'table_chart',
      'xlsx': 'table_chart',
      'ppt': 'slideshow',
      'pptx': 'slideshow',
      'png': 'image',
      'jpg': 'image',
      'jpeg': 'image',
      'gif': 'image',
      'svg': 'image'
    };
    return iconMap[ext] || 'insert_drive_file';
  }

  isSourcePreviewable(source: any): boolean {
    const viewMode = source.viewMode || '';
    return viewMode !== 'DOWNLOAD_ONLY';
  }

  formatFileSize(bytes: number): string {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  trackBySourceId(index: number, source: any): string {
    return source.id;
  }

  trackByDocId(index: number, doc: any): string {
    return doc.id || index.toString();
  }

  objectKeys(obj: any): string[] {
    return obj ? Object.keys(obj) : [];
  }

  formatThreshold(value: number): string {
    return value.toFixed(2);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // INDEX STORAGE SETTINGS METHODS (per-sheet)
  // ═══════════════════════════════════════════════════════════════════════════════

  saveIndexStorageSettings(): void {
    if (!this.activeSheet) {
      this.showError('No active fact sheet selected');
      return;
    }

    this.isIndexStorageSaving = true;
    this.indexStorageSaveMessage = null;

    // Update the active sheet with the new index paths
    // Send empty string explicitly to clear paths (backend will normalize to null for global defaults)
    // Only include paths that have changed to avoid unnecessary updates
    const request: UpdateFactSheetRequest = {};

    // Always include paths when saving index storage settings
    // Empty string means "clear to use global defaults"
    // Non-empty string means "use this specific path"
    request.vectorStorePath = this.editVectorStorePath || '';
    request.keywordIndexPath = this.editKeywordIndexPath || '';

    this.factSheetService.updateSheet(this.activeSheet.id, request).pipe(
      finalize(() => {
        this.isIndexStorageSaving = false;
        this.cdr.markForCheck();
      })
    ).subscribe({
      next: (updatedSheet) => {
        this.indexStorageSaveMessage = 'Index storage paths saved for "' + updatedSheet.name + '"';
        this.showSuccess(this.indexStorageSaveMessage);

        // Clear message after 5 seconds
        setTimeout(() => {
          this.indexStorageSaveMessage = null;
          this.cdr.markForCheck();
        }, 5000);
      },
      error: (err) => {
        console.error('Failed to save index storage settings', err);
        this.showError('Failed to save index storage settings.');
      }
    });
  }

  hasIndexStorageChanges(): boolean {
    if (!this.activeSheet) return false;
    return this.editVectorStorePath !== (this.activeSheet.vectorStorePath || '') ||
      this.editKeywordIndexPath !== (this.activeSheet.keywordIndexPath || '');
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // MODEL CONFIGURATION METHODS (per-sheet)
  // ═══════════════════════════════════════════════════════════════════════════════

  private loadArchiveModels(): void {
    // Subscribe to archive status
    this.archiveService.status$
      .pipe(takeUntil(this.destroy$))
      .subscribe(status => {
        this.isArchiveLoaded = status?.loaded || false;
        this.currentArchiveStatus = status;
        this.cdr.markForCheck();
      });

    // Subscribe to archive models
    this.archiveService.models$
      .pipe(takeUntil(this.destroy$))
      .subscribe(models => {
        this.allArchiveModels = models;
        this.encoderOptions = models.filter(m => m.type === 'encoder');
        this.crossEncoderOptions = models.filter(m => m.type === 'cross_encoder');
        this.cdr.markForCheck();
      });

    // Subscribe to available archives
    this.archiveService.archives$
      .pipe(takeUntil(this.destroy$))
      .subscribe(archives => {
        this.availableArchives = archives;
        this.cdr.markForCheck();
      });

    // Initial load of archive status and models
    this.isLoadingArchives = true;
    this.archiveService.getStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          if (status?.loaded) {
            this.archiveService.loadModels()
              .pipe(takeUntil(this.destroy$))
              .subscribe();
          }
        },
        error: () => {
          console.warn('Could not load archive status - archives may not be available');
        }
      });

    // Load available archives
    this.archiveService.listArchives()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.isLoadingArchives = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.isLoadingArchives = false;
          console.warn('Could not load available archives');
          this.cdr.markForCheck();
        }
      });
  }

  /**
   * Load a specific archive
   */
  loadArchive(archivePath: string): void {
    this.isLoadingArchives = true;
    this.archiveService.loadArchive(archivePath)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.isLoadingArchives = false;
          if (status.loaded) {
            this.showSuccess(`Archive loaded: ${status.archiveId || archivePath}`);
          }
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.isLoadingArchives = false;
          this.showError('Failed to load archive');
          this.cdr.markForCheck();
        }
      });
  }

  /**
   * Unload the current archive
   */
  unloadArchive(): void {
    this.isLoadingArchives = true;
    this.archiveService.unloadArchive()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.isLoadingArchives = false;
          this.showSuccess('Archive unloaded');
          this.cdr.markForCheck();
        },
        error: () => {
          this.isLoadingArchives = false;
          this.showError('Failed to unload archive');
          this.cdr.markForCheck();
        }
      });
  }

  /**
   * Refresh archive status and available models.
   * Called when switching fact sheets to ensure archive display is up to date.
   */
  refreshArchiveStatus(): void {
    // Refresh current archive status
    this.archiveService.getStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          if (status?.loaded) {
            // Refresh models list if archive is loaded
            this.archiveService.loadModels()
              .pipe(takeUntil(this.destroy$))
              .subscribe();
          }
          this.cdr.markForCheck();
        },
        error: () => {
          // Silently handle error - archive service may not be available
          this.cdr.markForCheck();
        }
      });

    // Also refresh the list of available archives
    this.archiveService.listArchives()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.cdr.markForCheck(),
        error: () => this.cdr.markForCheck()
      });
  }

  /**
   * Get active encoder model name for display
   */
  getActiveEncoderDisplay(): string {
    if (this.activeSheet?.embeddingModel) {
      const source = this.activeSheet.embeddingModelSource || 'registry';
      return `${this.activeSheet.embeddingModel} (${source})`;
    }
    // Find system default from loaded models
    if (this.allArchiveModels.length > 0) {
      const defaultEncoder = this.allArchiveModels.find(m => m.type === 'encoder');
      if (defaultEncoder) {
        return `${defaultEncoder.modelId} (system default)`;
      }
    }
    return 'System Default';
  }

  /**
   * Get active reranker model name for display
   */
  getActiveRerankerDisplay(): string {
    if (!this.activeSheet?.rerankingEnabled) {
      return 'Disabled';
    }
    const type = this.activeSheet.rerankerType || 'none';
    if (type === 'none') {
      return 'Disabled';
    }
    if (type === 'cross_encoder' && this.activeSheet.crossEncoderModel) {
      const source = this.activeSheet.crossEncoderModelSource || 'registry';
      return `${this.activeSheet.crossEncoderModel} (${source})`;
    }
    // Return the reranker type for algorithmic rerankers
    const rerankerType = this.rerankerTypes.find(r => r.value === type);
    return rerankerType?.label || type;
  }

  /**
   * Format file size for display
   */
  formatArchiveSize(bytes: number | null | undefined): string {
    if (!bytes) return 'Unknown';
    return formatFileSize(bytes);
  }

  /**
   * Load system-wide configuration (currently loaded embedding model, reranker, etc.)
   */
  loadSystemConfig(): void {
    this.isLoadingSystemConfig = true;

    // Also load embedding status to get the source (REGISTRY or ARCHIVE)
    this.localRegistryService.getEmbeddingStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.embeddingStatus = status;
        },
        error: (err) => {
          console.warn('Could not load embedding status', err);
        }
      });

    // Call /api/rag/test/status to get the actual loaded models
    this.http.get<any>(`${this.backendUrl}/rag/test/status`)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.systemConfig = {
            embeddingModel: response.embeddingModel ? {
              className: response.embeddingModel.class || 'Unknown',
              dimensions: response.embeddingModel.dimensions || 0,
              isNoOp: response.embeddingModel.class?.includes('NoOp') || false,
              modelId: response.embeddingModel.modelId || undefined
            } : null,
            reranker: response.reranker ? {
              available: response.reranker.available || false,
              className: response.reranker.class || 'Unknown',
              supportedTypes: response.reranker.supportedTypes || []
            } : null,
            vectorStore: response.vectorStore ? {
              className: response.vectorStore.class || 'Unknown',
              available: response.vectorStore.available || false
            } : null
          };
          this.isLoadingSystemConfig = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.warn('Could not load system configuration', err);
          this.isLoadingSystemConfig = false;
          this.cdr.markForCheck();
        }
      });
  }

  /**
   * Get a friendly display name for the system embedding model
   */
  getSystemEncoderDisplayName(): string {
    if (!this.systemConfig.embeddingModel) {
      return 'Not loaded';
    }
    const className = this.systemConfig.embeddingModel.className;
    // Extract model name from class name (e.g., "AnseriniEmbeddingModelImpl" -> "Anserini Embedding")
    const simpleName = className.split('.').pop() || className;
    const dimensions = this.systemConfig.embeddingModel.dimensions;
    if (this.systemConfig.embeddingModel.isNoOp) {
      return 'Disabled (NoOp)';
    }
    return dimensions > 0 ? `${simpleName} (${dimensions}d)` : simpleName;
  }

  /**
   * Get a friendly display name for the system reranker
   */
  getSystemRerankerDisplayName(): string {
    if (!this.systemConfig.reranker || !this.systemConfig.reranker.available) {
      return 'Disabled';
    }
    const className = this.systemConfig.reranker.className;
    const simpleName = className.split('.').pop() || className;
    const types = this.systemConfig.reranker.supportedTypes;
    if (types && types.length > 0) {
      return `${simpleName} (${types.join(', ')})`;
    }
    return simpleName;
  }

  /**
   * Get a friendly display name for the system vector store
   */
  getSystemVectorStoreDisplayName(): string {
    if (!this.systemConfig.vectorStore || !this.systemConfig.vectorStore.available) {
      return 'Not configured';
    }
    const className = this.systemConfig.vectorStore.className;
    return className.split('.').pop() || className;
  }

  private updateModelConfigFormFromSheet(sheet: FactSheet): void {
    this.editEmbeddingModel = sheet.embeddingModel;
    this.editEmbeddingModelSource = (sheet.embeddingModelSource as ModelSourceType) || 'default';
    this.editEmbeddingArchiveId = sheet.embeddingArchiveId;
    this.editRerankingEnabled = sheet.rerankingEnabled || false;
    this.editRerankerType = sheet.rerankerType || 'none';
    this.editCrossEncoderModel = sheet.crossEncoderModel;
    this.editCrossEncoderModelSource = (sheet.crossEncoderModelSource as ModelSourceType) || 'default';
    this.editCrossEncoderArchiveId = sheet.crossEncoderArchiveId;
    this.editRerankTopK = sheet.rerankTopK ?? 10;
    this.editMmrLambda = sheet.mmrLambda ?? 0.5;
    // Graph config
    this.editEnableGraphBuilding = sheet.enableGraphBuilding || false;
    this.editGraphBuilderType = sheet.graphBuilderType || 'llm';
    this.editGraphStorageType = sheet.graphStorageType || 'jpa';
  }

  saveModelConfig(): void {
    if (!this.activeSheet) {
      this.showError('No active fact sheet selected');
      return;
    }

    // Check if embedding model is changing and sheet has indexed facts
    const embeddingModelChanging = this.editEmbeddingModel !== this.activeSheet.embeddingModel;
    const hasIndexedFacts = (this.activeSheet.indexedCount || 0) > 0;

    if (embeddingModelChanging && hasIndexedFacts) {
      const oldModel = this.activeSheet.embeddingModel || 'default';
      const newModel = this.editEmbeddingModel || 'default';
      const indexedCount = this.activeSheet.indexedCount;

      const dialogData: ConfirmDialogData = {
        title: 'Change Embedding Model',
        message: `WARNING: Changing the embedding model from "${oldModel}" to "${newModel}" will invalidate the existing vector store.\n\n${indexedCount} indexed document(s) will be marked as unindexed and will need to be reindexed with the new model before search will work correctly.\n\nDo you want to proceed?`,
        confirmText: 'Proceed',
        confirmColor: 'warn',
        icon: 'warning'
      };

      this.dialog.open(ConfirmDialogComponent, { data: dialogData })
        .afterClosed()
        .pipe(
          filter(confirmed => confirmed === true),
          takeUntil(this.destroy$)
        )
        .subscribe(() => {
          this.doSaveModelConfig();
        });
    } else {
      this.doSaveModelConfig();
    }
  }

  private doSaveModelConfig(): void {
    if (!this.activeSheet) return;

    this.isModelConfigSaving = true;
    this.modelConfigSaveMessage = null;

    // Build the update request with model config
    const request: UpdateFactSheetRequest = {
      embeddingModel: this.editEmbeddingModel || undefined,
      embeddingModelSource: this.editEmbeddingModelSource !== 'default' ? this.editEmbeddingModelSource : undefined,
      embeddingArchiveId: this.editEmbeddingArchiveId || undefined,
      rerankingEnabled: this.editRerankingEnabled,
      rerankerType: (this.editRerankerType && this.editRerankerType !== 'none') ? this.editRerankerType : undefined,
      crossEncoderModel: this.editCrossEncoderModel || undefined,
      crossEncoderModelSource: this.editCrossEncoderModelSource !== 'default' ? this.editCrossEncoderModelSource : undefined,
      crossEncoderArchiveId: this.editCrossEncoderArchiveId || undefined,
      rerankTopK: this.editRerankTopK ?? undefined,
      mmrLambda: this.editMmrLambda ?? undefined
    };

    this.factSheetService.updateSheet(this.activeSheet.id, request)
      .pipe(
        finalize(() => {
          this.isModelConfigSaving = false;
          this.cdr.markForCheck();
        })
      )
      .subscribe({
        next: (updatedSheet) => {
          // Check if embedding model changed and facts were marked unindexed
          const wasModelChanged = this.editEmbeddingModel !== this.activeSheet?.embeddingModel;
          const needsReindex = updatedSheet.needsReindex;

          if (wasModelChanged && needsReindex) {
            this.modelConfigSaveMessage = `Model configuration saved. Embedding model changed - ${updatedSheet.unindexedCount} fact(s) marked for reindexing.`;
            this.showSuccess(this.modelConfigSaveMessage);

            // Refresh the sheet to show the needsReindex warning
            this.factSheetService.loadActiveSheet().subscribe();
          } else {
            this.modelConfigSaveMessage = `Model configuration saved for "${updatedSheet.name}"`;
            this.showSuccess(this.modelConfigSaveMessage);
          }

          // Clear message after 5 seconds
          setTimeout(() => {
            this.modelConfigSaveMessage = null;
            this.cdr.markForCheck();
          }, 5000);
        },
        error: (err) => {
          console.error('Failed to save model configuration', err);
          this.showError('Failed to save model configuration');
        }
      });
  }

  /** Check if the embedding model is being changed */
  isEmbeddingModelChanging(): boolean {
    if (!this.activeSheet) return false;
    return this.editEmbeddingModel !== this.activeSheet.embeddingModel;
  }

  /** Check if the embedding model change will require reindexing */
  willEmbeddingChangeRequireReindex(): boolean {
    if (!this.activeSheet) return false;
    return this.isEmbeddingModelChanging() && (this.activeSheet.indexedCount || 0) > 0;
  }

  hasModelConfigChanges(): boolean {
    if (!this.activeSheet) return false;

    return this.editEmbeddingModel !== this.activeSheet.embeddingModel ||
      this.editEmbeddingModelSource !== (this.activeSheet.embeddingModelSource || 'default') ||
      this.editEmbeddingArchiveId !== this.activeSheet.embeddingArchiveId ||
      this.editRerankingEnabled !== (this.activeSheet.rerankingEnabled || false) ||
      this.editRerankerType !== (this.activeSheet.rerankerType || 'none') ||
      this.editCrossEncoderModel !== this.activeSheet.crossEncoderModel ||
      this.editCrossEncoderModelSource !== (this.activeSheet.crossEncoderModelSource || 'default') ||
      this.editCrossEncoderArchiveId !== this.activeSheet.crossEncoderArchiveId ||
      this.editRerankTopK !== (this.activeSheet.rerankTopK ?? 10) ||
      this.editMmrLambda !== (this.activeSheet.mmrLambda ?? 0.5);
  }

  onEmbeddingModelSourceChange(): void {
    // Clear model selection when source changes
    if (this.editEmbeddingModelSource !== 'archive') {
      this.editEmbeddingArchiveId = null;
    }
    if (this.editEmbeddingModelSource === 'default') {
      this.editEmbeddingModel = null;
    }
  }

  onCrossEncoderModelSourceChange(): void {
    // Clear model selection when source changes
    if (this.editCrossEncoderModelSource !== 'archive') {
      this.editCrossEncoderArchiveId = null;
    }
    if (this.editCrossEncoderModelSource === 'default') {
      this.editCrossEncoderModel = null;
    }
  }

  onRerankerTypeChange(): void {
    // Enable/disable reranking based on type
    if (this.editRerankerType === 'none') {
      this.editRerankingEnabled = false;
    } else {
      this.editRerankingEnabled = true;
    }
  }

  needsCrossEncoder(): boolean {
    return this.editRerankerType === 'cross_encoder';
  }

  needsMmrLambda(): boolean {
    return this.editRerankerType === 'mmr';
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // KNOWLEDGE GRAPH CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load available graph storage types from the backend.
   */
  loadAvailableStorageTypes(): void {
    this.http.get<{
      availableTypes: string[];
      allTypes: { type: string; available: boolean; description: string }[];
      defaultType: string;
    }>(`${this.backendUrl}/knowledge-graph/builder/storage-types`)
    .subscribe({
      next: (response) => {
        // Convert the response to our format
        if (response.allTypes) {
          this.availableStorageTypes = response.allTypes;
        } else {
          // Build from availableTypes list
          const available = new Set(response.availableTypes || []);
          this.availableStorageTypes = this.graphStorageTypes.map(st => ({
            type: st.value,
            available: available.has(st.value),
            description: st.description
          }));
        }
        console.log('[GraphConfig] Available storage types:', this.availableStorageTypes);
      },
      error: (err) => {
        console.warn('[GraphConfig] Failed to load storage types, using defaults:', err);
        // Fallback to static list
        this.availableStorageTypes = [
          { type: 'jpa', available: true, description: 'Local Database (JPA/JDBC)' },
          { type: 'neo4j', available: false, description: 'Neo4j (not configured)' }
        ];
      }
    });
  }

  /**
   * Save knowledge graph configuration for the active sheet.
   */
  saveGraphConfig(): void {
    if (!this.activeSheet) {
      this.showError('No active fact sheet selected');
      return;
    }

    this.isGraphConfigSaving = true;
    this.graphConfigSaveMessage = null;

    const request: UpdateFactSheetRequest = {
      enableGraphBuilding: this.editEnableGraphBuilding,
      graphBuilderType: this.editGraphBuilderType,
      graphStorageType: this.editGraphStorageType
    };

    this.factSheetService.updateSheet(this.activeSheet.id, request)
      .pipe(
        finalize(() => {
          this.isGraphConfigSaving = false;
          this.cdr.markForCheck();
        })
      )
      .subscribe({
        next: (updatedSheet) => {
          this.graphConfigSaveMessage = `Knowledge graph settings saved for "${updatedSheet.name}"`;
          this.showSuccess(this.graphConfigSaveMessage);

          // Clear message after 5 seconds
          setTimeout(() => {
            this.graphConfigSaveMessage = null;
            this.cdr.markForCheck();
          }, 5000);
        },
        error: (err) => {
          console.error('Failed to save knowledge graph configuration', err);
          this.showError('Failed to save knowledge graph configuration');
        }
      });
  }

  /**
   * Check if there are unsaved graph configuration changes.
   */
  hasGraphConfigChanges(): boolean {
    if (!this.activeSheet) return false;

    return this.editEnableGraphBuilding !== (this.activeSheet.enableGraphBuilding || false) ||
      this.editGraphBuilderType !== (this.activeSheet.graphBuilderType || 'llm') ||
      this.editGraphStorageType !== (this.activeSheet.graphStorageType || 'jpa');
  }

  /**
   * Check if a storage type is available.
   */
  isStorageTypeAvailable(type: string): boolean {
    const storageType = this.availableStorageTypes.find(st => st.type === type);
    return storageType?.available ?? (type === 'jpa'); // JPA is always available by default
  }

  /**
   * Get display label for a storage type.
   */
  getStorageTypeLabel(type: string): string {
    const storageType = this.graphStorageTypes.find(st => st.value === type);
    return storageType?.label ?? type;
  }

  /**
   * Navigate to Model Staging tab to load an embedding model.
   * Emits an event to the parent app component to switch tabs.
   */
  navigateToModelStaging(): void {
    this.openModelStaging.emit();
  }

  /**
   * Check if vector search is ready (embedding model properly initialized).
   */
  isVectorSearchReady(): boolean {
    // Check both embeddingStatus (from LocalRegistryService) and systemConfig
    if (this.embeddingStatus) {
      return this.embeddingStatus.initialized &&
        this.embeddingStatus.dimensions > 0 &&
        !this.embeddingStatus.modelId?.includes('NOT_INITIALIZED');
    }
    // Fallback to systemConfig check
    return !!(this.systemConfig.embeddingModel &&
      !this.systemConfig.embeddingModel.isNoOp &&
      this.systemConfig.embeddingModel.dimensions > 0);
  }

  /**
   * Get available reranker methods as a comma-separated string.
   */
  getRerankerMethods(): string {
    if (!this.systemConfig.reranker?.supportedTypes) {
      return 'Available';
    }
    const types = this.systemConfig.reranker.supportedTypes;
    if (types.length === 0) {
      return 'Available';
    }
    // Show first 3 methods, then "..."
    if (types.length <= 3) {
      return types.join(', ');
    }
    return types.slice(0, 3).join(', ') + ` +${types.length - 3} more`;
  }
}
