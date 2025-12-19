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

import { Component, OnInit, OnDestroy, ViewChild, ElementRef, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
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
import { takeUntil, debounceTime, distinctUntilChanged, finalize } from 'rxjs/operators';

import { FactSheetService } from '../../services/fact-sheet.service';
import { DocumentService } from '../../services/document.service';
import { WebSocketService, WebSocketConnectionState } from '../../services/websocket.service';
import { ConfluenceService, ConfluenceIngestRequest } from '../../services/confluence.service';
import { IndexBrowserService } from '../../services/index-browser.service';
import { SourceViewerService } from '../../services/source-viewer.service';
import { ArchiveService } from '../../services/archive.service';
import { SourceViewerDialogComponent, SourceViewerDialogData } from '../source-viewer-dialog/source-viewer-dialog.component';
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
  AsyncUploadResponse,
  BatchAsyncUploadResponse,
  YouTubeTranscriptResponse,
  IngestLogEntry,
  ArchiveModelInfo,
  ArchiveInfo,
  ArchiveStatus,
  ModelSourceType
} from '../../models/api-models';
import { AddSourceDialogComponent, AddSourceDialogData } from '../document-manager/add-source-dialog/add-source-dialog.component';
import { PipelineSettingsPanelComponent } from '../document-manager/pipeline-settings-panel/pipeline-settings-panel.component';
import { SubprocessLogsComponent } from '../subprocess-logs/subprocess-logs.component';

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
    SubprocessLogsComponent
  ],
  templateUrl: './fact-sheet-manager.component.html',
  styleUrls: ['./fact-sheet-manager.component.css']
})
export class FactSheetManagerComponent implements OnInit, OnDestroy {
  @ViewChild('fileInput') fileInput!: ElementRef<HTMLInputElement>;
  @ViewChild('keywordPaginator') keywordPaginator!: MatPaginator;
  @ViewChild('vectorPaginator') vectorPaginator!: MatPaginator;

  // Backend URL for API calls
  private backendUrl: string;

  // Sheet state
  sheets: FactSheet[] = [];
  activeSheet: FactSheet | null = null;
  isLoading = false;

  // Facts state
  facts: Fact[] = [];
  selectedFact: Fact | null = null;
  factsLoading = false;

  // Async upload tracking (same granularity as document-manager)
  activeUploads: Map<string, IngestProgressUpdate> = new Map();
  activeUploadsArray: IngestProgressUpdate[] = [];
  wsConnectionState: WebSocketConnectionState = WebSocketConnectionState.DISCONNECTED;
  expandedPipelines: Set<string> = new Set();
  expandedBatchDetails: Set<string> = new Set();
  expandedJobInfo: Set<string> = new Set();
  expandedSubprocessDetails: Set<string> = new Set();
  expandedSubprocessLogs: Set<string> = new Set();
  taskLogs: Map<string, IngestLogEntry[]> = new Map();
  private cancelledTaskIds: Set<string> = new Set();
  private wsSubscription: Subscription | null = null;
  private logsSubscription: Subscription | null = null;
  private connectionSubscription: Subscription | null = null;
  private taskPollingSubscription: Subscription | null = null;

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
    embeddingModel: { className: string; dimensions: number; isNoOp: boolean } | null;
    reranker: { available: boolean; className: string; supportedTypes: string[] } | null;
    vectorStore: { className: string; available: boolean } | null;
  } = { embeddingModel: null, reranker: null, vectorStore: null };
  isLoadingSystemConfig = false;

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

  private destroy$ = new Subject<void>();

  constructor(
    private factSheetService: FactSheetService,
    private documentService: DocumentService,
    private webSocketService: WebSocketService,
    private confluenceService: ConfluenceService,
    private indexBrowserService: IndexBrowserService,
    private sourceViewerService: SourceViewerService,
    private archiveService: ArchiveService,
    private http: HttpClient,
    private fb: FormBuilder,
    private snackBar: MatSnackBar,
    private dialog: MatDialog,
    private cdr: ChangeDetectorRef
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
        }
      });

    // Now load data after subscriptions are ready
    this.loadData();
    this.loadAvailableLoadersAndChunkers();
    this.loadActiveIngestTasks();
    this.initializeWebSocket();
    this.startTaskPolling();
    this.loadIndexerStatus();
    this.loadArchiveModels();
    this.loadSystemConfig();

    // Subscribe to facts
    this.factSheetService.facts$
      .pipe(takeUntil(this.destroy$))
      .subscribe(facts => this.facts = facts);

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
    console.log(`[FactSheetManager] Received progress update: taskId=${update.taskId.substring(0,8)}, factSheetId=${update.factSheetId}, status=${update.status}, activeSheetId=${this.activeSheet?.id}`);

    const existing = this.activeUploads.get(update.taskId);
    const merged = this.mergeProgressUpdate(existing, update);
    this.activeUploads.set(update.taskId, merged);

    // Auto-expand logs and job info panel for new tasks so users can see real-time logs immediately
    if (!existing && update.status === IngestStatus.IN_PROGRESS) {
      this.expandedSubprocessLogs.add(update.taskId);
      this.expandedJobInfo.add(update.taskId);
    }

    this.updateFilteredUploadsArray();

    // Remove completed/failed/cancelled tasks after a delay
    if (update.status === IngestStatus.COMPLETED ||
      update.status === IngestStatus.FAILED ||
      update.status === IngestStatus.CANCELLED) {
      setTimeout(() => {
        this.activeUploads.delete(update.taskId);
        this.cancelledTaskIds.delete(update.taskId);
        this.updateFilteredUploadsArray();
      }, 10000);

      // Refresh facts on completion
      if (update.status === IngestStatus.COMPLETED) {
        this.loadFacts();
        this.factSheetService.loadSheets().subscribe();
        this.showSuccess(`'${update.fileName}' processed successfully!`);
      } else if (update.status === IngestStatus.FAILED) {
        this.showError(`Failed to process '${update.fileName}': ${update.errorMessage}`);
      } else if (update.status === IngestStatus.CANCELLED) {
        this.showSuccess(`'${update.fileName}' was cancelled`);
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
        currentEmbeddingBatch: update.stats.currentEmbeddingBatch || existing.stats.currentEmbeddingBatch,
        subprocessRuntimeInfo: update.stats.subprocessRuntimeInfo || existing.stats.subprocessRuntimeInfo,
        workerStatuses: update.stats.workerStatuses || existing.stats.workerStatuses,
      };
    } else if (existing.stats && !update.stats) {
      mergedStats = existing.stats;
    }

    // Preserve factSheetId - use update's value if present, otherwise keep existing
    const mergedFactSheetId = update.factSheetId !== null && update.factSheetId !== undefined
      ? update.factSheetId
      : existing.factSheetId;

    return { ...existing, ...update, stats: mergedStats, factSheetId: mergedFactSheetId };
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
      console.log(`[FactSheetManager] All uploads factSheetIds:`, allUploads.map(u => ({ taskId: u.taskId.substring(0,8), factSheetId: u.factSheetId, status: u.status })));
    }

    this.activeUploadsArray = filtered.map(u => ({ ...u }));
    this.cdr.markForCheck();
    this.cdr.detectChanges();
  }

  // Progress tracking helpers
  hasActiveProcessing(): boolean {
    return this.activeUploadsArray.some(u => u.status === IngestStatus.IN_PROGRESS);
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
    const phaseOrder = ['QUEUED', 'LOADING', 'CONVERTING', 'CHUNKING', 'EMBEDDING', 'INDEXING', 'COMPLETED'];
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
    const indexed = upload.stats.documentsIndexed || upload.stats.chunksIndexed || 0;
    return Math.round((indexed / upload.stats.chunksCreated) * 100);
  }

  // Detect passthrough mode: embedding is done by indexer
  isPassthroughMode(upload: IngestProgressUpdate): boolean {
    if (!upload.stats) return false;
    const embedded = upload.stats.chunksEmbedded || 0;
    const indexed = upload.stats.chunksIndexed || 0;
    return embedded === 0 && indexed > 0;
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

  calculateETA(upload: IngestProgressUpdate): string | null {
    if (!upload.stats || !upload.stats.chunksPerSecond || upload.stats.chunksPerSecond <= 0) return null;

    const chunksCreated = upload.stats.chunksCreated || 0;
    const chunksIndexed = upload.stats.chunksIndexed || upload.stats.documentsIndexed || 0;
    const remaining = chunksCreated - chunksIndexed;

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

    if (!confirm(`Delete "${fact.fileName}"?`)) return;

    this.factSheetService.deleteFact(fact.id).subscribe({
      next: () => {
        if (this.selectedFact?.id === fact.id) {
          this.selectedFact = null;
        }
        this.showSuccess(`Deleted "${fact.fileName}"`);
      },
      error: () => this.showError('Failed to delete fact')
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

    const request: CreateFactSheetRequest = {
      name: this.sheetForm.value.name,
      description: this.sheetForm.value.description || undefined,
      color: this.sheetForm.value.color,
      icon: this.sheetForm.value.icon
    };

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

    if (!confirm(`Delete "${sheet.name}" and all ${sheet.factCount} facts?`)) return;

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
    this.factSheetService.updateSheet(this.activeSheet.id, {
      vectorStorePath: this.editVectorStorePath || undefined,
      keywordIndexPath: this.editKeywordIndexPath || undefined
    }).pipe(
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

    // Call /api/rag/test/status to get the actual loaded models
    this.http.get<any>(`${this.backendUrl}/rag/test/status`)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.systemConfig = {
            embeddingModel: response.embeddingModel ? {
              className: response.embeddingModel.class || 'Unknown',
              dimensions: response.embeddingModel.dimensions || 0,
              isNoOp: response.embeddingModel.class?.includes('NoOp') || false
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
  }

  saveModelConfig(): void {
    if (!this.activeSheet) {
      this.showError('No active fact sheet selected');
      return;
    }

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
          this.modelConfigSaveMessage = `Model configuration saved for "${updatedSheet.name}"`;
          this.showSuccess(this.modelConfigSaveMessage);

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
}
