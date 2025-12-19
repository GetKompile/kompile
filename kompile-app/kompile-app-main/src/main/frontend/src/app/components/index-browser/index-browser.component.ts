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

import { Component, OnInit, ViewChild, ChangeDetectorRef, AfterViewInit, OnDestroy } from '@angular/core';
import { MatTableDataSource } from '@angular/material/table';
import { MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSort } from '@angular/material/sort';
import { FormControl } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { IndexBrowserService } from '../../services/index-browser.service';
import { WebSocketService } from '../../services/websocket.service';
import { SourceViewerService } from '../../services/source-viewer.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { CrossIndexService } from '../../services/cross-index.service';
import {
  IndexedDocInfo,
  SearchResult,
  SearchResponse,
  IndexBrowserStatus,
  JobStatus,
  JobState,
  VectorPopulationUpdate,
  VectorPopulationStats,
  VectorPopulationWorkerStatus,
  VectorPopulationQueueStatus,
  VectorPopulationEmbeddingBatchMetrics,
  VectorPopulationPhase,
  VectorPopulationStatus,
  getVectorPopulationPhaseDisplayName,
  getVectorPopulationPhaseIcon,
  getVectorPopulationStatusColor,
  IngestLogEntry,
  VectorPopulationTaskEnvironment,
  Nd4jEnvironmentConfig,
  SourceInfo,
  SourceListResponse,
  formatFileSize,
  getSourceViewModeIcon,
  FactSheet
} from '../../models/api-models';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CdkTextareaAutosize } from '@angular/cdk/text-field';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { Subscription, timer } from 'rxjs';
import { SubprocessLogsComponent } from '../subprocess-logs/subprocess-logs.component';
import { SourceViewerDialogComponent, SourceViewerDialogData } from '../source-viewer-dialog/source-viewer-dialog.component';

// Legacy interface for backward compatibility - maps to new VectorPopulationUpdate
interface VectorPopulationProgress {
  taskId: string;
  phase: string;
  progressPercent: number;
  message: string;
  currentStep?: string;
  stats?: {
    documentsProcessed: number;
    chunksCreated: number;
    chunksEmbedded: number;
    chunksIndexed: number;
    totalDocuments: number;
    throughputDocsPerSec: number;
    elapsedTimeMs: number;
    // Actual index store counts (verified after commit to persistence)
    actualKeywordIndexCount?: number;
    actualVectorStoreCount?: number;
    workerStatuses?: VectorPopulationWorkerStatus[];
    queueStatus?: VectorPopulationQueueStatus;
    currentEmbeddingBatch?: VectorPopulationEmbeddingBatchMetrics;
  };
}

interface DisplayItem {
  id: string;
  preview: string;
  score?: number;
  originalDocument?: string;
  metadata?: { [key: string]: any };
  lucene_internal_id?: number;
  content?: string;
  isSearchResult?: boolean;
}

// Tab indices
const TAB_KEYWORD_INDEX = 0;
const TAB_VECTOR_STORE = 1;

@Component({
  selector: 'app-index-browser',
  standalone: false,
  templateUrl: './index-browser.component.html',
  styleUrls: ['./index-browser.component.css']
})
export class IndexBrowserComponent implements OnInit, AfterViewInit, OnDestroy {
  // Tab control (0 = keyword index, 1 = vector store)
  selectedTabIndex = 0 as number;

  // Keyword Index data source
  dataSource = new MatTableDataSource<DisplayItem>();
  displayedColumns: string[] = ['id', 'preview', 'score', 'originalDocument', 'actions'];

  // Vector Store data source
  vectorDataSource = new MatTableDataSource<DisplayItem>();

  // Keyword Index Search functionality
  searchControl = new FormControl('');
  searchResults: SearchResult[] = [];
  isSearchMode: boolean = false;
  currentSearchQuery: string = '';
  maxSearchResults: number = 20;

  // Vector Store Search functionality
  vectorSearchControl = new FormControl('');
  vectorSearchResults: SearchResult[] = [];
  isVectorSearchMode: boolean = false;
  currentVectorSearchQuery: string = '';
  vectorSimilarityThreshold: number = 0.0;  // Minimum cosine similarity threshold (0.0 to 1.0)

  // Status information
  indexBrowserStatus: IndexBrowserStatus | null = null;
  isLoadingStatus: boolean = false;

  // General state
  isLoading = false;
  totalDocsEstimate = 0;
  pageSize = 10;
  currentPage = 0;

  // Vector Store state
  isLoadingVector = false;
  totalVectorDocsEstimate = 0;
  vectorPageSize = 10;
  vectorCurrentPage = 0;

  // Document details
  selectedDoc: DisplayItem | null = null;
  editedContent: string = '';

  // Pipeline settings panel
  showPipelineSettings = false;

  @ViewChild(MatPaginator) paginator!: MatPaginator;
  @ViewChild('vectorPaginator') vectorPaginator!: MatPaginator;
  @ViewChild(MatSort) sort!: MatSort;
  @ViewChild('autosize') autosize!: CdkTextareaAutosize;
  @ViewChild('subprocessLogs') subprocessLogsComponent!: SubprocessLogsComponent;

  // Sources/Facts tab data
  sourcesDataSource = new MatTableDataSource<SourceInfo>();
  sourcesDisplayedColumns: string[] = ['fileName', 'extension', 'size', 'viewMode', 'sourceType', 'actions'];
  factsDisplayedColumns: string[] = ['select', 'fileName', 'extension', 'size', 'viewMode', 'sourceType', 'actions'];
  isLoadingSources = false;
  totalSourcesCount = 0;
  sourcesPageSize = 20;
  sourcesCurrentPage = 0;
  sourcesFilter = '';

  // Fact Sheets
  factSheets: FactSheet[] = [];
  activeFactSheet: FactSheet | null = null;
  selectedFactIds: Set<string | number> = new Set();

  // Cross-index status
  crossIndexPendingCount = 0;

  // Helper functions for templates
  formatFileSize = formatFileSize;
  getSourceViewModeIcon = getSourceViewModeIcon;

  @ViewChild('sourcesPaginator') sourcesPaginator!: MatPaginator;

  constructor(
    private indexBrowserService: IndexBrowserService,
    private websocketService: WebSocketService,
    private sourceViewerService: SourceViewerService,
    private factSheetService: FactSheetService,
    private crossIndexService: CrossIndexService,
    private dialog: MatDialog,
    private cdr: ChangeDetectorRef,
    private snackBar: MatSnackBar
  ) { }

  ngOnInit(): void {
    this.loadStatus();
    this.loadDocuments();
    this.setupSearchControl();
    this.setupVectorSearchControl();
    // Restore active vector population task state on page load/reload
    this.restoreActiveVectorPopulationState();
    // Load fact sheets
    this.loadFactSheets();
    // Load cross-index summary for the badge count
    this.loadCrossIndexSummary();

    // Subscribe to active sheet changes
    this.factSheetService.activeSheet$.subscribe(sheet => {
      if (sheet && (!this.activeFactSheet || sheet.id !== this.activeFactSheet.id)) {
        this.activeFactSheet = sheet;
        this.loadSources();
        this.cdr.detectChanges();
      }
    });
  }

  ngAfterViewInit() {
    if (this.paginator) {
      this.dataSource.paginator = this.paginator;
      this.paginator.page.subscribe((event: PageEvent) => {
        if (!this.isSearchMode) {
          this.pageSize = event.pageSize;
          this.currentPage = event.pageIndex;
          this.loadDocuments(this.currentPage * this.pageSize, this.pageSize);
        }
      });
    }
    if (this.sort) {
      this.dataSource.sort = this.sort;
    }
  }

  loadStatus(): void {
    this.isLoadingStatus = true;
    this.indexBrowserService.getIndexBrowserStatus().subscribe({
      next: (status) => {
        this.indexBrowserStatus = status;
        this.isLoadingStatus = false;
        this.cdr.detectChanges();

        if (status.warning) {
          this.snackBar.open(status.warning, 'Close', {
            duration: 10000,
            panelClass: ['snackbar-warning']
          });
        }
      },
      error: (err) => {
        this.isLoadingStatus = false;
        this.snackBar.open(`Error loading status: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  setupSearchControl(): void {
    this.searchControl.valueChanges.pipe(
      debounceTime(300),
      distinctUntilChanged()
    ).subscribe(query => {
      if (query && query.trim().length > 0) {
        this.performSearch(query.trim());
      } else {
        this.clearSearch();
      }
    });
  }

  performSearch(query: string): void {
    this.isLoading = true;
    this.isSearchMode = true;
    this.currentSearchQuery = query;
    this.selectedDoc = null;

    this.indexBrowserService.searchIndexedDocs(query, this.maxSearchResults).subscribe({
      next: (response: SearchResponse) => {
        this.searchResults = response.results;
        this.updateDataSourceWithSearchResults(response.results);
        this.isLoading = false;
        this.cdr.detectChanges();

        this.snackBar.open(`Found ${response.totalResults} results for "${query}"`, 'Close', {
          duration: 3000,
          panelClass: ['snackbar-success']
        });
      },
      error: (err) => {
        this.isLoading = false;
        this.isSearchMode = false;
        this.snackBar.open(`Search failed: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  clearSearch(): void {
    this.isSearchMode = false;
    this.currentSearchQuery = '';
    this.searchResults = [];
    this.searchControl.setValue('', { emitEvent: false });
    this.loadDocuments();
  }

  updateDataSourceWithSearchResults(results: SearchResult[]): void {
    const displayItems: DisplayItem[] = results.map(result => ({
      id: result.id,
      preview: result.preview,
      score: result.score,
      originalDocument: result.originalDocument,
      metadata: result.metadata,
      content: result.content,
      isSearchResult: true
    }));

    this.dataSource.data = displayItems;
    this.totalDocsEstimate = displayItems.length;

    // Disable pagination for search results
    if (this.paginator) {
      this.paginator.length = displayItems.length;
      this.paginator.pageIndex = 0;
    }
  }

  loadDocuments(offset: number = 0, limit: number = this.pageSize): void {
    this.isLoading = true;
    this.selectedDoc = null;
    this.indexBrowserService.getAllIndexedDocs(offset, limit).subscribe({
      next: (docs) => {
        const displayItems: DisplayItem[] = docs.map(doc => ({
          id: doc.id,
          preview: doc.preview || '[No preview]',
          metadata: doc.metadata,
          lucene_internal_id: doc.lucene_internal_id,
          content: doc.content,
          isSearchResult: false
        }));

        this.dataSource.data = displayItems;
        if (docs.length < limit) {
          this.totalDocsEstimate = offset + docs.length;
        } else {
          this.totalDocsEstimate = offset + docs.length + 1;
        }
        if (this.paginator) {
          this.paginator.length = this.totalDocsEstimate;
        }
        this.isLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.isLoading = false;
        this.snackBar.open(`Error loading documents: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  viewDocument(doc: DisplayItem): void {
    if (doc.isSearchResult && doc.content) {
      // For search results, we already have the content
      this.selectedDoc = doc;
      this.editedContent = doc.content || '';
      this.cdr.detectChanges();
    } else {
      // For browse results, we need to fetch the full document
      this.isLoading = true;
      this.indexBrowserService.getIndexedDoc(doc.id).subscribe({
        next: (fullDoc) => {
          this.selectedDoc = {
            ...doc,
            content: fullDoc.content,
            metadata: fullDoc.metadata
          };
          this.editedContent = fullDoc.content || '';
          this.isLoading = false;
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isLoading = false;
          this.snackBar.open(`Error loading document ${doc.id}: ${err.message || 'Server error'}`, 'Close', {
            duration: 5000,
            panelClass: ['snackbar-error']
          });
        }
      });
    }
  }

  closeDocumentView(): void {
    this.selectedDoc = null;
    this.editedContent = '';
  }

  saveDocument(): void {
    if (!this.selectedDoc || this.editedContent === null) return;
    this.isLoading = true;
    this.indexBrowserService.updateIndexedDoc(this.selectedDoc.id, this.editedContent).subscribe({
      next: (response) => {
        this.snackBar.open(response.message || `Document ${this.selectedDoc?.id} updated.`, 'Close', {
          duration: 3000,
          panelClass: ['snackbar-success']
        });
        this.isLoading = false;
        if (this.selectedDoc) {
          this.viewDocument(this.selectedDoc);
        }
      },
      error: (err) => {
        this.isLoading = false;
        this.snackBar.open(`Error updating document ${this.selectedDoc?.id}: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  // Vector Indexing Job State
  isVectorIndexing = false;
  vectorIndexJobStatus: JobStatus | null = null;
  vectorPopulationProgress: VectorPopulationProgress | null = null;
  currentVectorTaskId: string | null = null;
  private statusPollingSub: Subscription | null = null;
  private wsProgressSub: Subscription | null = null;
  private documentRefreshSub: Subscription | null = null;
  private lastActualVectorCount: number | null = null;

  // Expandable panel tracking (matching document-manager pattern)
  expandedJobInfo = false;
  expandedBatchDetails = false;
  expandedWorkerDetails = false;
  expandedLogs = false;
  expandedEnvironment = false;

  // Subprocess logs tracking
  showSubprocessLogs = true;  // Show logs by default during vector population

  // Task environment tracking
  taskEnvironments: Map<string, VectorPopulationTaskEnvironment> = new Map();
  showRawJson = false;

  // Helper methods for expandable panels
  toggleJobInfo(): void {
    this.expandedJobInfo = !this.expandedJobInfo;
  }

  toggleBatchDetails(): void {
    this.expandedBatchDetails = !this.expandedBatchDetails;
  }

  toggleWorkerDetails(): void {
    this.expandedWorkerDetails = !this.expandedWorkerDetails;
  }

  toggleLogs(): void {
    this.expandedLogs = !this.expandedLogs;
  }

  toggleSubprocessLogs(): void {
    this.showSubprocessLogs = !this.showSubprocessLogs;
  }

  toggleEnvironment(): void {
    this.expandedEnvironment = !this.expandedEnvironment;
    // Fetch environment if not already loaded
    if (this.expandedEnvironment && this.currentVectorTaskId && !this.taskEnvironments.has(this.currentVectorTaskId)) {
      this.fetchTaskEnvironment(this.currentVectorTaskId);
    }
  }

  /**
   * Fetch the ND4J environment snapshot for a task.
   */
  fetchTaskEnvironment(taskId: string): void {
    this.indexBrowserService.getVectorPopulationTaskEnvironment(taskId).subscribe({
      next: (envResponse) => {
        this.taskEnvironments.set(taskId, envResponse);
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to fetch task environment:', err);
        this.taskEnvironments.set(taskId, {
          available: true,
          found: false,
          taskId: taskId,
          environmentCaptured: false,
          message: 'Failed to fetch environment: ' + err.message
        });
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Get the task environment for a given taskId.
   */
  getTaskEnvironment(taskId: string): VectorPopulationTaskEnvironment | null {
    return this.taskEnvironments.get(taskId) || null;
  }

  /**
   * Check if task environment is available.
   */
  hasTaskEnvironment(taskId: string): boolean {
    const env = this.taskEnvironments.get(taskId);
    return env?.environmentCaptured === true;
  }

  // Format duration for display
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

  // Get workers by type
  getWorkersByType(workers: VectorPopulationWorkerStatus[] | undefined, type: string): VectorPopulationWorkerStatus[] {
    if (!workers) return [];
    return workers.filter(w => w.workerType === type);
  }

  // Get total processed by worker type
  getTotalProcessed(workers: VectorPopulationWorkerStatus[] | undefined, type: string): number {
    if (!workers) return 0;
    return workers
      .filter(w => w.workerType === type)
      .reduce((sum, w) => sum + (w.itemsProcessed || 0), 0);
  }

  // Get queue fill percentage
  getQueueFillPercent(used: number, capacity: number): number {
    if (!capacity) return 0;
    return Math.round((used / capacity) * 100);
  }

  // Calculate embedding progress (uses totalDocuments since vector population skips chunking)
  getEmbeddingProgress(): number {
    const stats = this.vectorPopulationProgress?.stats;
    const total = stats?.totalDocuments || stats?.chunksCreated || 0;
    if (total === 0) return 0;
    return Math.round(((stats?.chunksEmbedded || 0) / total) * 100);
  }

  // Calculate indexing progress (uses totalDocuments since vector population skips chunking)
  getIndexingProgress(): number {
    const stats = this.vectorPopulationProgress?.stats;
    const total = stats?.totalDocuments || stats?.chunksCreated || 0;
    if (total === 0) return 0;
    return Math.round(((stats?.chunksIndexed || 0) / total) * 100);
  }

  // Calculate ETA
  calculateETA(): string | null {
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

  // Track by function for worker status ngFor
  trackByWorkerId(index: number, worker: VectorPopulationWorkerStatus): string {
    return `${worker.workerType}-${worker.workerId}`;
  }

  // Helper methods for displaying vector population status
  getPhaseDisplayName(phase: string): string {
    return getVectorPopulationPhaseDisplayName(phase as VectorPopulationPhase);
  }

  getPhaseIcon(phase: string): string {
    return getVectorPopulationPhaseIcon(phase as VectorPopulationPhase);
  }

  getVectorPopulationColor(status: string): string {
    return getVectorPopulationStatusColor(status as VectorPopulationStatus);
  }

  // Handle pipeline settings changes
  onPipelineSettingsChanged(config: any): void {
    // The pipeline settings panel handles configuration changes internally
    // This callback is used if we need to react to settings changes
    console.log('Pipeline settings changed:', config);
    // Could trigger a re-validation or refresh here if needed
    this.snackBar.open('Pipeline settings updated', 'Close', { duration: 2000 });
  }

  createVectorIndexFromLucene(): void {
    if (this.isVectorIndexing) {
      return;
    }

    this.isLoading = true;
    this.snackBar.open('Initiating vector index creation with pipeline...', 'Close', { duration: 3000 });

    // Subscribe to WebSocket progress first
    this.subscribeToVectorProgress();

    // Use new pipeline-based endpoint
    this.indexBrowserService.startVectorPopulation().subscribe({
      next: (response: any) => {
        this.isLoading = false;
        this.isVectorIndexing = true;
        this.currentVectorTaskId = response.taskId;

        // Initialize progress display
        this.vectorPopulationProgress = {
          taskId: response.taskId,
          phase: 'LOADING',
          progressPercent: 0,
          message: 'Starting vector population...'
        };

        this.snackBar.open(response.message || 'Vector population started. Watch progress below.', 'Close', {
          duration: 5000,
          panelClass: ['snackbar-success']
        });

        // Initialize subprocess logs if component is available
        if (this.subprocessLogsComponent && this.currentVectorTaskId) {
          this.subprocessLogsComponent.setTaskId(this.currentVectorTaskId);
        }
        this.expandedLogs = true;  // Auto-expand logs panel

        // Also start polling as fallback
        this.startStatusPolling();
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.isLoading = false;
        this.unsubscribeFromVectorProgress();
        this.snackBar.open(`Error creating vector index: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  /**
   * Restore active vector population state on page load/reload.
   * Uses the in-memory tracker for active tasks. Logs are NOT restored -
   * only real-time WebSocket updates are shown. Historical job data is
   * available in Job History.
   */
  private restoreActiveVectorPopulationState(): void {
    console.log('[RESTORE] Checking for active vector population tasks...');

    // Use the tracked tasks endpoint (in-memory tracker)
    this.indexBrowserService.getActiveTrackedVectorPopulationTasks().subscribe({
      next: (response) => {
        console.log('[RESTORE] Response received:', response.activeCount, 'active tasks');
        if (response.available && response.activeCount > 0 && response.tasks && response.tasks.length > 0) {
          // Take the first active task (there should usually be only one)
          const task = response.tasks[0];

          console.log('Restoring active vector population task:', task.taskId, 'phase:', task.phase);

          // Restore task state
          this.currentVectorTaskId = task.taskId;
          this.isVectorIndexing = true;

          // Map the server task to our progress object
          this.vectorPopulationProgress = {
            taskId: task.taskId,
            phase: String(task.phase) || 'LOADING',
            progressPercent: task.progressPercent || 0,
            message: task.message || 'Resuming...',
            currentStep: task.currentStep,
            stats: task.stats ? {
              documentsProcessed: task.stats.documentsLoaded || 0,
              chunksCreated: task.stats.chunksCreated || 0,
              chunksEmbedded: task.stats.chunksEmbedded || 0,
              chunksIndexed: task.stats.chunksIndexed || 0,
              totalDocuments: task.stats.totalDocuments || 0,
              throughputDocsPerSec: task.stats.throughputDocsPerSec || 0,
              elapsedTimeMs: task.stats.elapsedTimeMs || 0,
              workerStatuses: task.stats.workerStatuses,
              queueStatus: task.stats.queueStatus,
              currentEmbeddingBatch: task.stats.currentEmbeddingBatch
            } : undefined
          };

          // Update job status for compatibility
          this.vectorIndexJobStatus = {
            state: this.mapPhaseToJobState(String(task.phase)),
            message: task.message,
            percentComplete: task.progressPercent,
            documentsProcessed: task.stats?.documentsLoaded || 0
          };

          // Expand logs panel to show progress
          this.expandedLogs = true;
          this.showSubprocessLogs = true;
          this.cdr.detectChanges();

          // Subscribe to WebSocket for new updates (both progress and logs)
          this.subscribeToVectorProgress();

          // Set up subprocess logs component for real-time logs (no restoration of old logs)
          const setupLogs = (attempt: number) => {
            if (this.subprocessLogsComponent) {
              console.log(`[RESTORE] Setting up subprocess logs for task ${task.taskId}`);
              this.subprocessLogsComponent.logSource = 'vector-population';
              this.subprocessLogsComponent.taskId = task.taskId;
              this.subprocessLogsComponent.clearLogs(); // Start fresh
              this.subprocessLogsComponent.subscribeToLogs();
            } else if (attempt < 5) {
              setTimeout(() => setupLogs(attempt + 1), 100 * (attempt + 1));
            }
          };
          setTimeout(() => setupLogs(1), 100);

          // Start polling as fallback
          this.startStatusPolling();

          this.snackBar.open('Resumed active vector population task. Real-time updates active.', 'Close', {
            duration: 5000,
            panelClass: ['snackbar-info']
          });

          this.cdr.detectChanges();
        } else {
          console.log('No active vector population tasks found');
        }
      },
      error: (err) => {
        console.warn('Could not check for active vector population tasks:', err);
        // Non-critical error - page will still work, just won't restore state
      }
    });
  }

  private subscribeToVectorProgress(): void {
    this.unsubscribeFromVectorProgress();

    // Connect WebSocket if not connected
    console.log('[WS-PROGRESS] Connecting WebSocket and subscribing to vector population progress');
    this.websocketService.connect();

    this.wsProgressSub = this.websocketService
      .subscribeToVectorPopulation<VectorPopulationProgress>()
      .subscribe({
        next: (progress: VectorPopulationProgress) => {
          console.log('[WS-PROGRESS] Received progress update:', progress.taskId, progress.phase, progress.progressPercent + '%');
          // Only process updates for our task
          if (!this.currentVectorTaskId || progress.taskId === this.currentVectorTaskId) {
            this.vectorPopulationProgress = progress;
            this.cdr.detectChanges();

            // Update job status for compatibility
            this.vectorIndexJobStatus = {
              state: this.mapPhaseToJobState(progress.phase),
              message: progress.message,
              percentComplete: progress.progressPercent,
              documentsProcessed: progress.stats?.documentsProcessed || 0
            };

            // Auto-refresh document list when actual vector count changes
            const newActualCount = progress.stats?.actualVectorStoreCount;
            if (newActualCount != null && newActualCount !== this.lastActualVectorCount) {
              console.log('[INDEX-BROWSER] Actual vector count changed:', this.lastActualVectorCount, '->', newActualCount);
              this.lastActualVectorCount = newActualCount;
              // Update the displayed vector count for paginator
              this.totalVectorDocsEstimate = newActualCount;
              // ALSO update the status display count so the UI shows the correct number
              if (this.indexBrowserStatus) {
                this.indexBrowserStatus.approximateVectorCount = newActualCount;
              }
              // Refresh the vector store document list if viewing that tab
              if (this.selectedTabIndex === TAB_VECTOR_STORE) {
                this.loadVectorDocuments();
              }
            }

            // Also update keyword index count if available
            const newKeywordCount = progress.stats?.actualKeywordIndexCount;
            if (newKeywordCount != null) {
              this.totalDocsEstimate = newKeywordCount;
              // Also update the status display count
              if (this.indexBrowserStatus) {
                this.indexBrowserStatus.approximateDocumentCount = newKeywordCount;
              }
            }

            // Handle completion/failure (backend sends 'COMPLETED', not 'COMPLETE')
            if (progress.phase === 'COMPLETED') {
              this.handleVectorPopulationComplete(progress);
            } else if (progress.phase === 'FAILED') {
              this.handleVectorPopulationFailed(progress);
            }
          }
        },
        error: (err: Error) => {
          console.error('WebSocket progress error:', err);
        }
      });
  }

  private unsubscribeFromVectorProgress(): void {
    if (this.wsProgressSub) {
      this.wsProgressSub.unsubscribe();
      this.wsProgressSub = null;
    }
  }

  private mapPhaseToJobState(phase: string): JobState {
    switch (phase?.toUpperCase()) {
      case 'COMPLETE': return JobState.COMPLETED;
      case 'FAILED': return JobState.FAILED;
      case 'CANCELLED': return JobState.CANCELLED;
      default: return JobState.RUNNING;
    }
  }

  private handleVectorPopulationComplete(progress: VectorPopulationProgress): void {
    this.stopStatusPolling();
    this.unsubscribeFromVectorProgress();
    this.isVectorIndexing = false;

    const stats = progress.stats;
    const throughput = stats?.throughputDocsPerSec?.toFixed(1) || '0';
    const indexed = stats?.chunksIndexed || 0;

    this.snackBar.open(
      `Vector population complete! ${indexed} documents indexed (${throughput} docs/sec)`,
      'Close',
      { duration: 8000, panelClass: ['snackbar-success'] }
    );

    this.refreshData();
  }

  private handleVectorPopulationFailed(progress: VectorPopulationProgress): void {
    this.stopStatusPolling();
    this.unsubscribeFromVectorProgress();
    this.isVectorIndexing = false;

    this.snackBar.open(
      `Vector population failed: ${progress.message}`,
      'Close',
      { duration: 10000, panelClass: ['snackbar-error'] }
    );
  }

  cancelVectorIndexCreation(): void {
    if (!this.isVectorIndexing || !this.currentVectorTaskId) return;

    this.indexBrowserService.cancelVectorPopulation(this.currentVectorTaskId).subscribe({
      next: (response: any) => {
        this.snackBar.open('Cancellation requested...', 'Close', { duration: 3000 });
      },
      error: (err) => {
        this.snackBar.open(`Error cancelling job: ${err.message}`, 'Close', { duration: 5000, panelClass: ['snackbar-error'] });
      }
    });
  }

  private startStatusPolling(): void {
    if (this.statusPollingSub) {
      this.statusPollingSub.unsubscribe();
    }

    console.log('[POLLING] Starting status polling, currentVectorTaskId:', this.currentVectorTaskId);

    // Poll every 2 seconds as fallback (WebSocket is primary)
    this.statusPollingSub = timer(1000, 2000).subscribe(() => {
      if (this.currentVectorTaskId) {
        console.log('[POLLING] Fetching status for task:', this.currentVectorTaskId);
        // Poll for current task status using the tracker endpoint
        this.indexBrowserService.getTrackedVectorPopulationTaskStatus(this.currentVectorTaskId).subscribe({
          next: (response) => {
            console.log('[POLLING] Response:', response.available, response.found, response.task ? 'has task' : 'no task');
            if (response.available && response.found && response.task) {
              const task = response.task;

              // Update progress from polled data
              this.vectorPopulationProgress = {
                taskId: task.taskId,
                phase: task.phase || 'LOADING',
                progressPercent: task.progressPercent || 0,
                message: task.message || '',
                currentStep: task.currentStep,
                stats: task.stats ? {
                  documentsProcessed: task.stats.documentsLoaded || 0,
                  chunksCreated: task.stats.chunksCreated || 0,
                  chunksEmbedded: task.stats.chunksEmbedded || 0,
                  chunksIndexed: task.stats.chunksIndexed || 0,
                  totalDocuments: task.stats.totalDocuments || 0,
                  throughputDocsPerSec: task.stats.throughputDocsPerSec || 0,
                  elapsedTimeMs: task.stats.elapsedTimeMs || 0,
                  workerStatuses: task.stats.workerStatuses,
                  queueStatus: task.stats.queueStatus,
                  currentEmbeddingBatch: task.stats.currentEmbeddingBatch
                } : undefined
              };

              this.vectorIndexJobStatus = {
                state: this.mapPhaseToJobState(task.phase),
                message: task.message,
                percentComplete: task.progressPercent,
                documentsProcessed: task.stats?.documentsLoaded || 0
              };

              this.cdr.detectChanges();

              // Handle completion/failure (compare as strings since backend sends enum as string)
              const phaseStr = String(task.phase);
              if (phaseStr === 'COMPLETED' || phaseStr === 'COMPLETE') {
                this.handleVectorPopulationComplete(this.vectorPopulationProgress);
              } else if (phaseStr === 'FAILED') {
                this.handleVectorPopulationFailed(this.vectorPopulationProgress);
              } else if (phaseStr === 'CANCELLED') {
                this.stopStatusPolling();
                this.unsubscribeFromVectorProgress();
                this.isVectorIndexing = false;
                this.snackBar.open('Vector index creation cancelled.', 'Close', { duration: 5000 });
              }
            } else if (response.available && !response.found) {
              // Task not found - might have completed/cleaned up
              console.log('Task no longer tracked, stopping polling');
              this.stopStatusPolling();
              this.isVectorIndexing = false;
            }
          },
          error: (err) => {
            console.error("Error polling task status", err);
          }
        });
      } else {
        // Fall back to legacy status endpoint when no specific task
        this.indexBrowserService.getVectorIndexJobStatus().subscribe({
          next: (status) => {
            // Only update if not getting WebSocket updates
            if (!this.vectorPopulationProgress || this.vectorPopulationProgress.progressPercent === 0) {
              this.vectorIndexJobStatus = status;
              this.cdr.detectChanges();
            }

            if (status.state === JobState.COMPLETED) {
              this.stopStatusPolling();
              this.unsubscribeFromVectorProgress();
              this.isVectorIndexing = false;
              this.snackBar.open('Vector index creation completed!', 'Close', { duration: 5000, panelClass: ['snackbar-success'] });
              this.refreshData();
            } else if (status.state === JobState.FAILED) {
              this.stopStatusPolling();
              this.unsubscribeFromVectorProgress();
              this.isVectorIndexing = false;
              this.snackBar.open(`Vector index creation failed: ${status.message}`, 'Close', { duration: 10000, panelClass: ['snackbar-error'] });
            } else if (status.state === JobState.CANCELLED) {
              this.stopStatusPolling();
              this.unsubscribeFromVectorProgress();
              this.isVectorIndexing = false;
              this.snackBar.open('Vector index creation cancelled.', 'Close', { duration: 5000 });
            }
          },
          error: (err) => {
            console.error("Error polling status", err);
          }
        });
      }
    });
  }

  private stopStatusPolling(): void {
    if (this.statusPollingSub) {
      this.statusPollingSub.unsubscribe();
      this.statusPollingSub = null;
    }
  }

  ngOnDestroy(): void {
    this.stopStatusPolling();
    this.unsubscribeFromVectorProgress();
    if (this.documentRefreshSub) {
      this.documentRefreshSub.unsubscribe();
      this.documentRefreshSub = null;
    }
  }

  refreshData(): void {
    this.loadStatus();
    if (this.isSearchMode && this.currentSearchQuery) {
      this.performSearch(this.currentSearchQuery);
    } else {
      this.loadDocuments(this.currentPage * this.pageSize, this.pageSize);
    }
  }

  getDisplayedColumnsForMode(): string[] {
    if (this.isSearchMode) {
      return ['id', 'preview', 'score', 'originalDocument', 'actions'];
    } else {
      return ['id', 'preview', 'actions'];
    }
  }

  formatScore(score: number | undefined): string {
    return score !== undefined ? score.toFixed(3) : 'N/A';
  }

  getStatusColor(): string {
    if (!this.indexBrowserStatus) return 'warn';

    if (this.indexBrowserStatus.isNoOpIndexer || this.indexBrowserStatus.isNoOpRetriever) {
      return 'warn';
    }

    if (!this.indexBrowserStatus.indexAvailable) {
      return 'warn';
    }

    return 'primary';
  }

  getStatusMessage(): string {
    if (!this.indexBrowserStatus) return 'Loading status...';

    if (this.indexBrowserStatus.isNoOpIndexer && this.indexBrowserStatus.isNoOpRetriever) {
      return 'Using NoOp implementations - no functionality available';
    }

    if (this.indexBrowserStatus.isNoOpIndexer) {
      return 'Using NoOp Indexer - document browsing not available';
    }

    if (this.indexBrowserStatus.isNoOpRetriever) {
      return 'Using NoOp Retriever - search functionality not available';
    }

    if (!this.indexBrowserStatus.indexAvailable) {
      return 'Index not available - may need to be built';
    }

    return 'Index available and ready';
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // TAB HANDLING
  // ═══════════════════════════════════════════════════════════════════════════

  onTabChange(index: number): void {
    this.selectedTabIndex = index;
    this.selectedDoc = null;

    if (index === TAB_VECTOR_STORE && this.vectorDataSource.data.length === 0 && !this.isLoadingVector) {
      // Load vector store documents when switching to vector tab for the first time
      this.loadVectorDocuments();
    }

    // Load sources when switching to Sources tab (index 2)
    if (index === 2 && this.sourcesDataSource.data.length === 0 && !this.isLoadingSources) {
      this.loadSources();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // VECTOR STORE METHODS
  // ═══════════════════════════════════════════════════════════════════════════

  setupVectorSearchControl(): void {
    this.vectorSearchControl.valueChanges.pipe(
      debounceTime(300),
      distinctUntilChanged()
    ).subscribe(query => {
      if (query && query.trim().length > 0) {
        this.performVectorSearch(query.trim());
      } else {
        this.clearVectorSearch();
      }
    });
  }

  performVectorSearch(query: string): void {
    this.isLoadingVector = true;
    this.isVectorSearchMode = true;
    this.currentVectorSearchQuery = query;
    this.selectedDoc = null;

    this.indexBrowserService.searchVectorStore(query, this.maxSearchResults, this.vectorSimilarityThreshold).subscribe({
      next: (response: any) => {
        this.vectorSearchResults = response.results;
        this.updateVectorDataSourceWithSearchResults(response.results);
        this.isLoadingVector = false;
        this.cdr.detectChanges();

        if (response.totalResults === 0 && response.message) {
          // Show detailed message when no results found
          this.snackBar.open(response.message, 'Close', {
            duration: 8000,
            panelClass: ['snackbar-warning']
          });
        } else {
          this.snackBar.open(
            `Vector search found ${response.totalResults} results for "${query}" (threshold: ${this.vectorSimilarityThreshold.toFixed(2)})`,
            'Close',
            { duration: 3000, panelClass: ['snackbar-success'] }
          );
        }
      },
      error: (err) => {
        this.isLoadingVector = false;
        this.isVectorSearchMode = false;
        this.snackBar.open(`Vector search failed: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  clearVectorSearch(): void {
    this.isVectorSearchMode = false;
    this.currentVectorSearchQuery = '';
    this.vectorSearchResults = [];
    this.vectorSearchControl.setValue('', { emitEvent: false });
    this.loadVectorDocuments();
  }

  updateVectorDataSourceWithSearchResults(results: SearchResult[]): void {
    const displayItems: DisplayItem[] = results.map(result => ({
      id: result.id,
      preview: result.preview,
      score: result.score,
      originalDocument: result.originalDocument,
      metadata: result.metadata,
      content: result.content,
      isSearchResult: true
    }));

    this.vectorDataSource.data = displayItems;
    this.totalVectorDocsEstimate = displayItems.length;

    if (this.vectorPaginator) {
      this.vectorPaginator.length = displayItems.length;
      this.vectorPaginator.pageIndex = 0;
    }
  }

  loadVectorDocuments(offset: number = 0, limit: number = this.vectorPageSize): void {
    this.isLoadingVector = true;
    this.selectedDoc = null;

    this.indexBrowserService.getVectorStoreDocuments(offset, limit).subscribe({
      next: (docs: any[]) => {
        const displayItems: DisplayItem[] = docs.map(doc => ({
          id: doc.id || doc.docId || 'unknown',
          preview: doc.preview || doc.content?.substring(0, 100) + '...' || '[No preview]',
          metadata: doc.metadata,
          content: doc.content,
          isSearchResult: false
        }));

        this.vectorDataSource.data = displayItems;
        if (docs.length < limit) {
          this.totalVectorDocsEstimate = offset + docs.length;
        } else {
          this.totalVectorDocsEstimate = offset + docs.length + 1;
        }
        if (this.vectorPaginator) {
          this.vectorPaginator.length = this.totalVectorDocsEstimate;
        }
        this.isLoadingVector = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.isLoadingVector = false;
        this.snackBar.open(`Error loading vector documents: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  onVectorPaginatorChange(event: PageEvent): void {
    if (!this.isVectorSearchMode) {
      this.vectorPageSize = event.pageSize;
      this.vectorCurrentPage = event.pageIndex;
      this.loadVectorDocuments(this.vectorCurrentPage * this.vectorPageSize, this.vectorPageSize);
    }
  }

  viewVectorDocument(doc: DisplayItem): void {
    // For vector store documents, we use what's available
    this.selectedDoc = doc;
    this.editedContent = doc.content || '';
    this.cdr.detectChanges();
  }

  getDisplayedColumnsForVectorMode(): string[] {
    if (this.isVectorSearchMode) {
      return ['id', 'preview', 'score', 'originalDocument', 'actions'];
    } else {
      return ['id', 'preview', 'actions'];
    }
  }

  refreshVectorData(): void {
    if (this.isVectorSearchMode && this.currentVectorSearchQuery) {
      this.performVectorSearch(this.currentVectorSearchQuery);
    } else {
      this.loadVectorDocuments(this.vectorCurrentPage * this.vectorPageSize, this.vectorPageSize);
    }
  }

  objectKeys = Object.keys;

  // Format threshold value for slider display
  formatThreshold(value: number): string {
    return value.toFixed(2);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SOURCES TAB METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load source files from the backend
   */
  loadSources(offset: number = 0, limit: number = this.sourcesPageSize): void {
    this.isLoadingSources = true;
    this.sourceViewerService.listSources(limit, offset).subscribe({
      next: (response: SourceListResponse) => {
        this.sourcesDataSource.data = response.sources;
        this.totalSourcesCount = response.totalCount;
        this.isLoadingSources = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.isLoadingSources = false;
        this.snackBar.open(`Error loading sources: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Refresh sources list
   */
  refreshSources(): void {
    this.loadSources(this.sourcesCurrentPage * this.sourcesPageSize, this.sourcesPageSize);
  }

  /**
   * Handle sources pagination change
   */
  onSourcesPaginatorChange(event: PageEvent): void {
    this.sourcesPageSize = event.pageSize;
    this.sourcesCurrentPage = event.pageIndex;
    this.loadSources(this.sourcesCurrentPage * this.sourcesPageSize, this.sourcesPageSize);
  }

  /**
   * Apply filter to sources table
   */
  applySourcesFilter(event: Event): void {
    const filterValue = (event.target as HTMLInputElement).value;
    this.sourcesFilter = filterValue.trim().toLowerCase();
    this.sourcesDataSource.filter = this.sourcesFilter;
    if (this.sourcesDataSource.paginator) {
      this.sourcesDataSource.paginator.firstPage();
    }
  }

  /**
   * Open the source viewer dialog for a file
   */
  openSourceViewer(source: SourceInfo): void {
    const dialogData: SourceViewerDialogData = {
      fileName: source.fileName,
      checksum: source.checksum,
      sourceInfo: source
    };

    this.dialog.open(SourceViewerDialogComponent, {
      width: '90vw',
      maxWidth: '1200px',
      maxHeight: '90vh',
      data: dialogData,
      panelClass: 'source-viewer-dialog-panel'
    });
  }

  /**
   * Download a source file
   */
  downloadSource(source: SourceInfo): void {
    if (source.checksum) {
      this.sourceViewerService.downloadFileByChecksum(source.checksum);
    } else {
      this.sourceViewerService.downloadFile(source.fileName);
    }
  }

  /**
   * Open source viewer for a document from the index (if original file is available)
   */
  viewOriginalSource(doc: DisplayItem): void {
    const originalDocument = doc.originalDocument || doc.metadata?.['source_filename'] || doc.metadata?.['source_path'];
    if (originalDocument) {
      const dialogData: SourceViewerDialogData = {
        fileName: originalDocument,
        checksum: doc.metadata?.['source_checksum'] || null
      };

      this.dialog.open(SourceViewerDialogComponent, {
        width: '90vw',
        maxWidth: '1200px',
        maxHeight: '90vh',
        data: dialogData,
        panelClass: 'source-viewer-dialog-panel'
      });
    } else {
      this.snackBar.open('Original source file not available for this document', 'Close', {
        duration: 3000
      });
    }
  }

  /**
   * Check if original source is available for a document
   */
  hasOriginalSource(doc: DisplayItem): boolean {
    return !!(doc.originalDocument || doc.metadata?.['source_filename'] || doc.metadata?.['source_path']);
  }

  /**
   * Get viewable badge class based on view mode
   */
  getViewModeBadgeClass(viewMode: string): string {
    switch (viewMode) {
      case 'TEXT': return 'badge-text';
      case 'IMAGE': return 'badge-image';
      case 'EMBEDDED': return 'badge-embedded';
      default: return 'badge-download';
    }
  }

  /**
   * Get human-readable view mode label
   */
  getViewModeLabel(viewMode: string): string {
    switch (viewMode) {
      case 'TEXT': return 'Text';
      case 'IMAGE': return 'Image';
      case 'EMBEDDED': return 'PDF';
      case 'DOWNLOAD_ONLY': return 'Download';
      default: return viewMode;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FACT SHEET METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load fact sheets
   */
  loadFactSheets(): void {
    this.factSheetService.loadSheets().subscribe({
      next: (sheets) => {
        this.factSheets = sheets;
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to load fact sheets:', err);
      }
    });

    this.factSheetService.loadActiveSheet().subscribe({
      next: (sheet) => {
        this.activeFactSheet = sheet;
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to load active fact sheet:', err);
      }
    });
  }

  /**
   * Handle fact sheet change from the manager component
   */
  onFactSheetChanged(sheet: FactSheet): void {
    this.activeFactSheet = sheet;
    this.loadFactSheets();
    this.loadSources();
    this.selectedFactIds.clear();
    this.cdr.detectChanges();
  }

  /**
   * Toggle selection of a fact
   */
  toggleFactSelection(factId: string | number): void {
    if (this.selectedFactIds.has(factId)) {
      this.selectedFactIds.delete(factId);
    } else {
      this.selectedFactIds.add(factId);
    }
    this.cdr.detectChanges();
  }

  /**
   * Toggle all facts selection
   */
  toggleAllFactSelection(checked: boolean): void {
    if (checked) {
      this.sourcesDataSource.data.forEach(fact => {
        const id = (fact as any).id || fact.fileName;
        this.selectedFactIds.add(id);
      });
    } else {
      this.selectedFactIds.clear();
    }
    this.cdr.detectChanges();
  }

  /**
   * Check if a fact is selected
   */
  isFactSelected(factId: string | number): boolean {
    return this.selectedFactIds.has(factId);
  }

  /**
   * Check if all facts are selected
   */
  isAllFactsSelected(): boolean {
    return this.sourcesDataSource.data.length > 0 &&
      this.selectedFactIds.size === this.sourcesDataSource.data.length;
  }

  /**
   * Check if some (but not all) facts are selected
   */
  isSomeFactsSelected(): boolean {
    return this.selectedFactIds.size > 0 &&
      this.selectedFactIds.size < this.sourcesDataSource.data.length;
  }

  /**
   * Copy selected facts to another sheet
   */
  copyFactsToSheet(targetSheetId: number): void {
    if (!this.activeFactSheet || this.selectedFactIds.size === 0) return;

    const factIds = Array.from(this.selectedFactIds).filter(id => typeof id === 'number') as number[];
    if (factIds.length === 0) {
      this.snackBar.open('No valid facts selected for copying', 'Close', { duration: 3000 });
      return;
    }

    this.factSheetService.copyFacts(this.activeFactSheet.id, targetSheetId, factIds).subscribe({
      next: (response) => {
        this.snackBar.open(`Copied ${response.copiedCount} facts to sheet`, 'Close', {
          duration: 3000,
          panelClass: ['snackbar-success']
        });
        this.loadFactSheets();
      },
      error: (err) => {
        this.snackBar.open('Failed to copy facts', 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  /**
   * Move selected facts to another sheet
   */
  moveFactsToSheet(targetSheetId: number): void {
    if (!this.activeFactSheet || this.selectedFactIds.size === 0) return;

    const factIds = Array.from(this.selectedFactIds).filter(id => typeof id === 'number') as number[];
    if (factIds.length === 0) {
      this.snackBar.open('No valid facts selected for moving', 'Close', { duration: 3000 });
      return;
    }

    this.factSheetService.moveFacts(this.activeFactSheet.id, targetSheetId, factIds).subscribe({
      next: (response) => {
        this.snackBar.open(`Moved ${response.movedCount} facts to sheet`, 'Close', {
          duration: 3000,
          panelClass: ['snackbar-success']
        });
        this.selectedFactIds.clear();
        this.loadSources();
        this.loadFactSheets();
      },
      error: (err) => {
        this.snackBar.open('Failed to move facts', 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  /**
   * Delete selected facts
   */
  deleteSelectedFacts(): void {
    if (this.selectedFactIds.size === 0) return;

    if (!confirm(`Are you sure you want to delete ${this.selectedFactIds.size} selected facts?`)) {
      return;
    }

    const factIds = Array.from(this.selectedFactIds).filter(id => typeof id === 'number') as number[];
    if (factIds.length === 0) {
      this.snackBar.open('No valid facts selected for deletion', 'Close', { duration: 3000 });
      return;
    }

    this.factSheetService.deleteFacts(factIds).subscribe({
      next: () => {
        this.snackBar.open(`Deleted ${factIds.length} facts`, 'Close', {
          duration: 3000,
          panelClass: ['snackbar-success']
        });
        this.selectedFactIds.clear();
        this.loadSources();
        this.loadFactSheets();
      },
      error: (err) => {
        this.snackBar.open('Failed to delete facts', 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CROSS-INDEX STATUS METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load cross-index summary to get pending count for the tab badge
   */
  loadCrossIndexSummary(): void {
    const factSheetId = this.activeFactSheet?.id || 1;
    this.crossIndexService.getCrossIndexSummaryForFactSheet(factSheetId).subscribe({
      next: (summary) => {
        // Count documents that are not fully indexed (partially synced + not indexed + out of sync)
        this.crossIndexPendingCount =
          (summary.partiallyIndexedDocuments || 0) +
          (summary.notIndexedDocuments || 0) +
          (summary.outOfSyncDocuments || 0);
        this.cdr.detectChanges();
      },
      error: (err) => {
        // Non-critical - just log and leave count at 0
        console.warn('Could not load cross-index summary:', err);
      }
    });
  }
}

