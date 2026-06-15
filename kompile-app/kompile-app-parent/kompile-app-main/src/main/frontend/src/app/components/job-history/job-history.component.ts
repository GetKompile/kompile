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

import { Component, OnInit, OnDestroy, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Subject, interval } from 'rxjs';
import { takeUntil, filter, throttleTime, bufferTime } from 'rxjs/operators';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import {
  JobHistoryService,
  IndexingJobHistory,
  JobStatistics,
  JobStatus,
  FailureReason,
  IngestPhase,
  JobSummary,
  IngestEvent,
  SubprocessEvent,
  SubprocessEventType,
  SubprocessStatistics
} from '../../services/job-history.service';
import { WebSocketService, WebSocketConnectionState } from '../../services/websocket.service';
import { IngestProgressUpdate, IngestStatus } from '../../models/api-models';

@Component({
  selector: 'app-job-history',
  standalone: false,
  templateUrl: './job-history.component.html',
  styleUrls: ['./job-history.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class JobHistoryComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();
  private wsSubscribed = false;
  private progressUpdates$ = new Subject<{ update: IngestProgressUpdate; contentType?: string }>();
  private cachedNd4jCategories: { name: string; icon: string; fields: { key: string; value: any }[] }[] | null = null;
  private cachedNd4jEnvironment: any = null;

  // Data
  jobs: IndexingJobHistory[] = [];
  statistics: JobStatistics | null = null;
  selectedJob: JobSummary | null = null;
  selectedSubprocessEvent: SubprocessEvent | null = null;
  events: IngestEvent[] = [];
  parsedNd4jEnvironment: any = null;
  showNd4jDetails = false;

  // Subprocess events data
  subprocessEvents: SubprocessEvent[] = [];
  subprocessStatistics: SubprocessStatistics | null = null;

  // WebSocket state
  wsConnected = false;

  // UI State
  loading = false;
  error: string | null = null;
  lastRefreshed: Date | null = null;
  viewMode: 'list' | 'stats' | 'detail' = 'list';
  autoRefresh = true;
  refreshIntervalSeconds = 30;

  // Filters
  lookbackHours = 24;
  statusFilter: JobStatus | 'ALL' = 'ALL';
  jobTypeFilter: 'ALL' | 'ingest' | 'vector-population' | 'embedding' | 'subprocess' = 'ALL';
  searchTerm = '';

  // Pagination
  currentPage = 0;
  pageSize = 20;
  totalJobs = 0;

  // Cleanup dialog
  showCleanupDialog = false;
  cleanupDays = 30;

  // Available status options
  statusOptions: (JobStatus | 'ALL')[] = ['ALL', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'MEMORY_KILLED', 'PAUSED'];

  constructor(
    private jobHistoryService: JobHistoryService,
    private webSocketService: WebSocketService,
    private cdr: ChangeDetectorRef,
    private dialog: MatDialog
  ) { }

  ngOnInit(): void {
    this.loadJobs();
    this.loadStatistics();
    this.loadSubprocessEvents();
    this.loadSubprocessStatistics();

    // Setup throttled progress updates to prevent UI crashes from rapid WebSocket messages
    this.setupThrottledProgressUpdates();

    // Connect to WebSocket and subscribe to all task updates
    this.initWebSocket();

    if (this.autoRefresh) {
      this.startAutoRefresh();
    }
  }

  /**
   * Setup throttled processing of progress updates to batch updates and prevent UI thrashing.
   * Updates are batched every 250ms to reduce change detection cycles.
   */
  private setupThrottledProgressUpdates(): void {
    this.progressUpdates$.pipe(
      bufferTime(250),
      filter(batch => batch.length > 0),
      takeUntil(this.destroy$)
    ).subscribe(batch => {
      let modified = false;
      let needsStatsReload = false;

      for (const { update, contentType } of batch) {
        const jobIndex = this.jobs.findIndex(j => j.taskId === update.taskId);

        if (jobIndex >= 0) {
          const job = this.jobs[jobIndex];
          job.progressPercent = update.progressPercent;
          job.lastPhase = update.phase as IngestPhase;

          if (update.status === IngestStatus.COMPLETED) {
            job.status = 'COMPLETED';
            needsStatsReload = true;
          } else if (update.status === IngestStatus.FAILED) {
            job.status = 'FAILED';
            needsStatsReload = true;
          } else if (update.status === IngestStatus.CANCELLED) {
            job.status = 'CANCELLED';
          } else if (update.status === IngestStatus.IN_PROGRESS) {
            job.status = 'RUNNING';
          }

          if (update.stats) {
            job.documentsLoaded = update.stats.documentsLoaded;
            job.chunksCreated = update.stats.chunksCreated;
            job.chunksEmbedded = update.stats.chunksEmbedded;
            job.documentsIndexed = update.stats.documentsIndexed;
          }

          modified = true;
        } else {
          // Job not in list - reload once at the end
          modified = true;
        }
      }

      if (modified) {
        this.jobs = [...this.jobs];
        this.cdr.markForCheck();
      }

      if (needsStatsReload) {
        this.loadStatistics();
      }
    });
  }

  /**
   * Initialize WebSocket connection and subscribe to all ingest task updates.
   * This provides real-time updates instead of relying solely on polling.
   */
  private initWebSocket(): void {
    // Monitor connection state
    this.webSocketService.connectionState$
      .pipe(takeUntil(this.destroy$))
      .subscribe(state => {
        this.wsConnected = state === WebSocketConnectionState.CONNECTED;
        console.log('JobHistory WebSocket state:', state);

        // Subscribe to all tasks when connected
        if (this.wsConnected && !this.wsSubscribed) {
          this.subscribeToAllTasks();
        }
      });

    // Connect to WebSocket
    this.webSocketService.connect();
  }

  /**
   * Subscribe to all ingest task updates for real-time job status.
   */
  private subscribeToAllTasks(): void {
    if (this.wsSubscribed) return;
    this.wsSubscribed = true;

    console.log('JobHistory: Subscribing to all task updates');

    // Subscribe to regular ingest task updates
    this.webSocketService.subscribeToAllTasks()
      .pipe(takeUntil(this.destroy$))
      .subscribe(update => {
        console.log('JobHistory: Received WebSocket update:', update.taskId, update.phase, update.progressPercent);
        this.handleProgressUpdate(update, 'ingest');
      });

    // Subscribe to vector population task updates
    this.webSocketService.subscribeToVectorPopulation<IngestProgressUpdate>()
      .pipe(takeUntil(this.destroy$))
      .subscribe(update => {
        console.log('JobHistory: Received vector population update:', update.taskId, update.phase, update.progressPercent);
        this.handleProgressUpdate(update, 'vector-population');
      });
  }

  /**
   * Handle real-time progress updates from WebSocket.
   * Queues updates for throttled processing to prevent UI thrashing.
   */
  private handleProgressUpdate(update: IngestProgressUpdate, contentType?: string): void {
    // Queue the update for throttled processing
    this.progressUpdates$.next({ update, contentType });
  }

  ngOnDestroy(): void {
    this.progressUpdates$.complete();
    this.destroy$.next();
    this.destroy$.complete();
  }

  /**
   * TrackBy function for events to improve rendering performance.
   */
  trackByEventIndex(index: number, event: IngestEvent): string {
    return `${event.timestamp}-${event.eventType}-${index}`;
  }

  // ===================== DATA LOADING =====================

  loadJobs(): void {
    this.loading = true;
    this.error = null;
    this.cdr.markForCheck();

    if (this.statusFilter === 'ALL') {
      this.jobHistoryService.getRecentJobs(this.lookbackHours).subscribe({
        next: (jobs) => {
          this.jobs = this.filterJobs(jobs);
          this.totalJobs = jobs.length;
          this.loading = false;
          this.lastRefreshed = new Date();
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = 'Failed to load job history';
          this.loading = false;
          this.cdr.markForCheck();
          console.error(err);
        }
      });
    } else {
      // Pass lookbackHours to ensure consistent time filtering across all status filters
      this.jobHistoryService.getJobsByStatus(this.statusFilter, this.lookbackHours).subscribe({
        next: (jobs) => {
          this.jobs = this.filterJobs(jobs);
          this.totalJobs = jobs.length;
          this.loading = false;
          this.lastRefreshed = new Date();
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = 'Failed to load job history';
          this.loading = false;
          this.cdr.markForCheck();
          console.error(err);
        }
      });
    }
  }

  loadStatistics(): void {
    this.jobHistoryService.getStatistics(this.lookbackHours).subscribe({
      next: (stats) => {
        this.statistics = stats;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load statistics:', err);
      }
    });
  }

  loadSubprocessEvents(): void {
    console.log('JobHistory: Loading subprocess events for last', this.lookbackHours, 'hours');
    this.jobHistoryService.getRecentSubprocessEvents(this.lookbackHours).subscribe({
      next: (events) => {
        console.log('JobHistory: Received', events?.length || 0, 'subprocess events:', events);
        this.subprocessEvents = events;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load subprocess events:', err);
      }
    });
  }

  loadSubprocessStatistics(): void {
    this.jobHistoryService.getSubprocessStatistics().subscribe({
      next: (stats) => {
        this.subprocessStatistics = stats;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load subprocess statistics:', err);
      }
    });
  }

  loadEvents(taskId: string): void {
    this.jobHistoryService.getTaskEvents(taskId).subscribe({
      next: (response) => {
        this.events = response.events || [];
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load events:', err);
        this.events = [];
        this.cdr.markForCheck();
      }
    });
  }

  loadJobDetail(taskId: string): void {
    this.loading = true;
    this.parsedNd4jEnvironment = null;
    this.cachedNd4jCategories = null;
    this.cachedNd4jEnvironment = null;
    this.showNd4jDetails = false;
    this.cdr.markForCheck();

    this.jobHistoryService.getJobSummary(taskId).subscribe({
      next: (summary) => {
        this.selectedJob = summary;
        this.viewMode = 'detail';
        this.loading = false;
        // Parse ND4J environment JSON if available
        if (summary.environment?.nd4jEnvironmentJson) {
          try {
            this.parsedNd4jEnvironment = JSON.parse(summary.environment.nd4jEnvironmentJson);
          } catch (e) {
            console.error('Failed to parse ND4J environment JSON:', e);
            this.parsedNd4jEnvironment = null;
          }
        }
        this.cdr.markForCheck();
        // Load events for this job
        this.loadEvents(taskId);
      },
      error: (err) => {
        this.error = `Failed to load job details for ${taskId}`;
        this.loading = false;
        this.cdr.markForCheck();
        console.error(err);
      }
    });
  }

  // ===================== FILTERING =====================

  filterJobs(jobs: IndexingJobHistory[]): IndexingJobHistory[] {
    let filtered = jobs;

    // Apply job type filter
    if (this.jobTypeFilter !== 'ALL' && this.jobTypeFilter !== 'subprocess') {
      filtered = filtered.filter(job => {
        if (this.jobTypeFilter === 'vector-population') {
          return job.contentType === 'vector-population';
        } else if (this.jobTypeFilter === 'embedding') {
          return job.contentType === 'embedding';
        } else {
          // 'ingest' - exclude vector-population, embedding, and subprocess
          return job.contentType !== 'vector-population' &&
                 job.contentType !== 'embedding' &&
                 job.contentType !== 'subprocess';
        }
      });
    }

    // Apply search filter
    if (this.searchTerm) {
      const term = this.searchTerm.toLowerCase();
      filtered = filtered.filter(job =>
        job.fileName.toLowerCase().includes(term) ||
        job.taskId.toLowerCase().includes(term) ||
        (job.loaderUsed && job.loaderUsed.toLowerCase().includes(term)) ||
        (job.embeddingModelUsed && job.embeddingModelUsed.toLowerCase().includes(term))
      );
    }

    return filtered;
  }

  /**
   * Convert subprocess events to IndexingJobHistory entries for unified display.
   */
  convertSubprocessEventsToJobs(events: SubprocessEvent[]): IndexingJobHistory[] {
    return events.map(event => ({
      id: event.id,
      taskId: event.taskId || `subprocess-${event.id}`,
      fileName: this.formatSubprocessEventType(event.eventType),
      contentType: 'subprocess',
      status: this.mapSubprocessEventToStatus(event.eventType),
      lastPhase: this.mapSubprocessEventToPhase(event.eventType),
      startTime: event.timestamp,
      embeddingModelUsed: event.modelId,
      errorMessage: event.errorMessage,
      failureReason: event.failureReason as any,
      // Store additional subprocess-specific data
      additionalDetails: JSON.stringify({
        subprocessEvent: true,
        eventType: event.eventType,
        restartAttemptNumber: event.restartAttemptNumber,
        maxRestartAttempts: event.maxRestartAttempts,
        exitCode: event.exitCode,
        heapBytes: event.heapBytes,
        batchSize: event.batchSize,
        threadCount: event.threadCount,
        embeddingDimensions: event.embeddingDimensions,
        encoderType: event.encoderType
      })
    } as IndexingJobHistory));
  }

  /**
   * Map subprocess event type to job status.
   */
  private mapSubprocessEventToStatus(eventType: SubprocessEventType): JobStatus {
    switch (eventType) {
      case 'SUBPROCESS_STARTED':
      case 'MODEL_LOADED':
      case 'SUBPROCESS_RESTART_SUCCESS':
        return 'COMPLETED';
      case 'SUBPROCESS_RESTARTING':
        return 'RUNNING';
      case 'SUBPROCESS_CRASHED':
      case 'MODEL_FAILED':
      case 'SUBPROCESS_RESTART_EXHAUSTED':
        return 'FAILED';
      case 'SUBPROCESS_STOPPED':
        return 'CANCELLED';
      default:
        return 'COMPLETED';
    }
  }

  /**
   * Map subprocess event type to ingest phase.
   */
  private mapSubprocessEventToPhase(eventType: SubprocessEventType): IngestPhase {
    switch (eventType) {
      case 'SUBPROCESS_STARTED':
        return 'LOADING';
      case 'MODEL_LOADED':
        return 'COMPLETED';
      case 'SUBPROCESS_RESTARTING':
        return 'EMBEDDING';
      case 'SUBPROCESS_CRASHED':
      case 'MODEL_FAILED':
      case 'SUBPROCESS_RESTART_EXHAUSTED':
        return 'FAILED';
      default:
        return 'COMPLETED';
    }
  }

  /**
   * Merge jobs and subprocess events, sorted by timestamp.
   */
  getMergedJobsAndSubprocessEvents(): IndexingJobHistory[] {
    if (this.jobTypeFilter === 'subprocess') {
      // Only show subprocess events
      return this.convertSubprocessEventsToJobs(this.subprocessEvents);
    }

    if (this.jobTypeFilter !== 'ALL') {
      // Don't include subprocess events for specific job type filters
      return this.jobs;
    }

    // Merge jobs and subprocess events for ALL view
    const subprocessJobs = this.convertSubprocessEventsToJobs(this.subprocessEvents);
    const allJobs = [...this.jobs, ...subprocessJobs];

    // Sort by timestamp descending
    return allJobs.sort((a, b) => {
      const timeA = new Date(a.startTime).getTime();
      const timeB = new Date(b.startTime).getTime();
      return timeB - timeA;
    });
  }

  onJobTypeFilterChange(): void {
    this.currentPage = 0;
    this.loadJobs();
  }

  /**
   * Get display name for job type
   */
  getJobTypeDisplay(contentType?: string): string {
    if (contentType === 'vector-population') {
      return 'Vector Population';
    } else if (contentType === 'embedding') {
      return 'Embedding Model';
    } else if (contentType === 'subprocess') {
      return 'Subprocess Event';
    }
    return 'Document Ingest';
  }

  /**
   * Get icon for job type
   */
  getJobTypeIcon(contentType?: string): string {
    if (contentType === 'vector-population') {
      return 'storage';
    } else if (contentType === 'embedding') {
      return 'model_training';
    } else if (contentType === 'subprocess') {
      return 'autorenew';
    }
    return 'upload_file';
  }

  onSearchChange(): void {
    this.loadJobs();
  }

  onStatusFilterChange(): void {
    this.currentPage = 0;
    this.loadJobs();
  }

  onLookbackChange(): void {
    this.loadJobs();
    this.loadStatistics();
  }

  // ===================== AUTO REFRESH =====================

  startAutoRefresh(): void {
    interval(this.refreshIntervalSeconds * 1000)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        if (this.viewMode === 'list') {
          this.loadJobs();
          this.loadSubprocessEvents();
        }
        this.loadStatistics();
        this.loadSubprocessStatistics();
      });
  }

  toggleAutoRefresh(): void {
    this.autoRefresh = !this.autoRefresh;
    if (this.autoRefresh) {
      this.startAutoRefresh();
    }
    this.cdr.markForCheck();
  }

  manualRefresh(): void {
    this.loadJobs();
    this.loadStatistics();
    this.loadSubprocessEvents();
    this.loadSubprocessStatistics();
  }

  // ===================== VIEW MODES =====================

  showList(): void {
    this.viewMode = 'list';
    this.selectedJob = null;
    this.selectedSubprocessEvent = null;
    this.parsedNd4jEnvironment = null;
    this.cachedNd4jCategories = null;
    this.cachedNd4jEnvironment = null;
    this.showNd4jDetails = false;
    this.loadJobs();
  }

  toggleNd4jDetails(): void {
    this.showNd4jDetails = !this.showNd4jDetails;
    this.cdr.markForCheck();
  }

  getNd4jConfigCategories(): { name: string; icon: string; fields: { key: string; value: any }[] }[] {
    if (!this.parsedNd4jEnvironment) return [];

    // Return cached result if environment hasn't changed
    if (this.cachedNd4jCategories && this.cachedNd4jEnvironment === this.parsedNd4jEnvironment) {
      return this.cachedNd4jCategories;
    }

    const env = this.parsedNd4jEnvironment;
    this.cachedNd4jEnvironment = env;
    const categories: { name: string; icon: string; fields: { key: string; value: any }[] }[] = [];

    // Threading configuration
    const threading = [];
    if (env.blasMajorOrderThreads !== undefined) threading.push({ key: 'BLAS Major Order Threads', value: env.blasMajorOrderThreads });
    if (env.blasMinorOrderThreads !== undefined) threading.push({ key: 'BLAS Minor Order Threads', value: env.blasMinorOrderThreads });
    if (env.ompNumThreads !== undefined) threading.push({ key: 'OMP Num Threads', value: env.ompNumThreads });
    if (threading.length > 0) {
      categories.push({ name: 'Threading', icon: 'memory', fields: threading });
    }

    // Debug settings
    const debug = [];
    if (env.debug !== undefined) debug.push({ key: 'Debug Mode', value: env.debug });
    if (env.verbose !== undefined) debug.push({ key: 'Verbose Mode', value: env.verbose });
    if (env.profiling !== undefined) debug.push({ key: 'Profiling', value: env.profiling });
    if (env.leaksDetector !== undefined) debug.push({ key: 'Leaks Detector', value: env.leaksDetector });
    if (debug.length > 0) {
      categories.push({ name: 'Debug', icon: 'bug_report', fields: debug });
    }

    // Memory settings
    const memory = [];
    if (env.maxPrimaryMemory !== undefined) memory.push({ key: 'Max Primary Memory', value: this.formatBytes(env.maxPrimaryMemory) });
    if (env.maxSpecialMemory !== undefined) memory.push({ key: 'Max Special Memory', value: this.formatBytes(env.maxSpecialMemory) });
    if (env.maxDeviceMemory !== undefined) memory.push({ key: 'Max Device Memory', value: this.formatBytes(env.maxDeviceMemory) });
    if (memory.length > 0) {
      categories.push({ name: 'Memory Limits', icon: 'sd_card', fields: memory });
    }

    // Performance thresholds
    const perf = [];
    if (env.tadThreshold !== undefined) perf.push({ key: 'TAD Threshold', value: env.tadThreshold });
    if (env.elementwiseThreshold !== undefined) perf.push({ key: 'Elementwise Threshold', value: env.elementwiseThreshold });
    if (env.gemvMPerpetualThreshold !== undefined) perf.push({ key: 'GEMV M-Perpetual Threshold', value: env.gemvMPerpetualThreshold });
    if (env.gemvNPerpetualThreshold !== undefined) perf.push({ key: 'GEMV N-Perpetual Threshold', value: env.gemvNPerpetualThreshold });
    if (perf.length > 0) {
      categories.push({ name: 'Performance', icon: 'speed', fields: perf });
    }

    // BLAS configuration
    const blas = [];
    if (env.enableBlas !== undefined) blas.push({ key: 'BLAS Enabled', value: env.enableBlas });
    if (env.helpersAllowed !== undefined) blas.push({ key: 'Helpers Allowed', value: env.helpersAllowed });
    if (env.blasSerializationEnabled !== undefined) blas.push({ key: 'BLAS Serialization', value: env.blasSerializationEnabled });
    if (env.openBlasThreads !== undefined) blas.push({ key: 'OpenBLAS Threads', value: env.openBlasThreads });
    if (blas.length > 0) {
      categories.push({ name: 'BLAS Configuration', icon: 'calculate', fields: blas });
    }

    // Lifecycle Tracking
    const lifecycle = [];
    if (env.lifecycleTracking !== undefined) lifecycle.push({ key: 'Lifecycle Tracking', value: env.lifecycleTracking });
    if (env.trackViews !== undefined) lifecycle.push({ key: 'Track Views', value: env.trackViews });
    if (env.trackDeletions !== undefined) lifecycle.push({ key: 'Track Deletions', value: env.trackDeletions });
    if (env.snapshotFiles !== undefined) lifecycle.push({ key: 'Snapshot Files', value: env.snapshotFiles });
    if (env.trackOperations !== undefined) lifecycle.push({ key: 'Track Operations', value: env.trackOperations });
    if (env.stackDepth !== undefined) lifecycle.push({ key: 'Stack Depth', value: env.stackDepth });
    if (env.reportInterval !== undefined) lifecycle.push({ key: 'Report Interval', value: env.reportInterval });
    if (env.maxDeletionHistory !== undefined) lifecycle.push({ key: 'Max Deletion History', value: env.maxDeletionHistory });
    if (lifecycle.length > 0) {
      categories.push({ name: 'Lifecycle Tracking', icon: 'track_changes', fields: lifecycle });
    }

    // Individual Trackers
    const trackers = [];
    if (env.ndArrayTracking !== undefined) trackers.push({ key: 'NDArray Tracking', value: env.ndArrayTracking });
    if (env.dataBufferTracking !== undefined) trackers.push({ key: 'DataBuffer Tracking', value: env.dataBufferTracking });
    if (env.tadCacheTracking !== undefined) trackers.push({ key: 'TAD Cache Tracking', value: env.tadCacheTracking });
    if (env.shapeCacheTracking !== undefined) trackers.push({ key: 'Shape Cache Tracking', value: env.shapeCacheTracking });
    if (env.opContextTracking !== undefined) trackers.push({ key: 'OpContext Tracking', value: env.opContextTracking });
    if (trackers.length > 0) {
      categories.push({ name: 'Individual Trackers', icon: 'visibility', fields: trackers });
    }

    // Function Tracing
    const funcTrace = [];
    if (env.funcTracePrintAllocate !== undefined) funcTrace.push({ key: 'Trace Allocate', value: env.funcTracePrintAllocate });
    if (env.funcTracePrintDeallocate !== undefined) funcTrace.push({ key: 'Trace Deallocate', value: env.funcTracePrintDeallocate });
    if (env.funcTracePrintJavaOnly !== undefined) funcTrace.push({ key: 'Java Only Traces', value: env.funcTracePrintJavaOnly });
    if (funcTrace.length > 0) {
      categories.push({ name: 'Function Tracing', icon: 'code', fields: funcTrace });
    }

    // Advanced Debugging
    const advDebug = [];
    if (env.logNativeNDArrayCreation !== undefined) advDebug.push({ key: 'Log Native NDArray Creation', value: env.logNativeNDArrayCreation });
    if (env.logNDArrayEvents !== undefined) advDebug.push({ key: 'Log NDArray Events', value: env.logNDArrayEvents });
    if (env.checkInputChange !== undefined) advDebug.push({ key: 'Check Input Change', value: env.checkInputChange });
    if (env.checkOutputChange !== undefined) advDebug.push({ key: 'Check Output Change', value: env.checkOutputChange });
    if (env.trackWorkspaceOpenClose !== undefined) advDebug.push({ key: 'Track Workspace Open/Close', value: env.trackWorkspaceOpenClose });
    if (env.variableTracingEnabled !== undefined) advDebug.push({ key: 'Variable Tracing', value: env.variableTracingEnabled });
    if (advDebug.length > 0) {
      categories.push({ name: 'Advanced Debugging', icon: 'developer_mode', fields: advDebug });
    }

    // Memory Deletion Settings
    const memDeletion = [];
    if (env.deleteShapeInfo !== undefined) memDeletion.push({ key: 'Delete Shape Info', value: env.deleteShapeInfo });
    if (env.deletePrimary !== undefined) memDeletion.push({ key: 'Delete Primary', value: env.deletePrimary });
    if (env.deleteSpecial !== undefined) memDeletion.push({ key: 'Delete Special', value: env.deleteSpecial });
    if (memDeletion.length > 0) {
      categories.push({ name: 'Memory Deletion', icon: 'delete_outline', fields: memDeletion });
    }

    // JavaCPP Settings
    const javacpp = [];
    if (env.javacppLoggerDebug !== undefined) javacpp.push({ key: 'Logger Debug', value: env.javacppLoggerDebug });
    if (env.javacppPathsFirst !== undefined) javacpp.push({ key: 'Paths First', value: env.javacppPathsFirst });
    if (javacpp.length > 0) {
      categories.push({ name: 'JavaCPP Settings', icon: 'extension', fields: javacpp });
    }

    // Other settings
    const other = [];
    if (env.cpuBlocked !== undefined) other.push({ key: 'CPU Blocked', value: env.cpuBlocked });
    if (env.helpersDev !== undefined) other.push({ key: 'Helpers Dev', value: env.helpersDev });
    if (env.helpersImpl !== undefined) other.push({ key: 'Helpers Impl', value: env.helpersImpl });
    if (other.length > 0) {
      categories.push({ name: 'Other', icon: 'settings', fields: other });
    }

    // Cache the result to avoid recalculation on every change detection cycle
    this.cachedNd4jCategories = categories;
    return categories;
  }

  showStats(): void {
    this.viewMode = 'stats';
    this.loadStatistics();
  }

  showDetail(job: IndexingJobHistory): void {
    // Check if this is a subprocess event (converted to job for display)
    if (job.contentType === 'subprocess') {
      // Extract the ID from taskId which has format 'subprocess-{id}'
      const idMatch = job.taskId.match(/^subprocess-(\d+)$/);
      if (idMatch) {
        const eventId = parseInt(idMatch[1], 10);
        this.loadSubprocessEventDetail(eventId);
      } else {
        // Fallback: use the job.id directly if it's the original event ID
        this.loadSubprocessEventDetail(job.id);
      }
    } else {
      this.loadJobDetail(job.taskId);
    }
  }

  loadSubprocessEventDetail(eventId: number): void {
    this.loading = true;
    this.selectedJob = null;
    this.selectedSubprocessEvent = null;
    this.parsedNd4jEnvironment = null;
    this.cachedNd4jCategories = null;
    this.cachedNd4jEnvironment = null;
    this.showNd4jDetails = false;
    this.cdr.markForCheck();

    this.jobHistoryService.getSubprocessEventById(eventId).subscribe({
      next: (event) => {
        this.selectedSubprocessEvent = event;
        this.viewMode = 'detail';
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = `Failed to load subprocess event details for ID ${eventId}`;
        this.loading = false;
        this.cdr.markForCheck();
        console.error(err);
      }
    });
  }

  // ===================== CLEANUP =====================

  openCleanupDialog(): void {
    this.showCleanupDialog = true;
    this.cdr.markForCheck();
  }

  closeCleanupDialog(): void {
    this.showCleanupDialog = false;
    this.cdr.markForCheck();
  }

  performCleanup(): void {
    this.loading = true;
    this.cdr.markForCheck();
    this.jobHistoryService.forceCleanup(this.cleanupDays).subscribe({
      next: (response) => {
        console.log(`Cleanup complete: ${response.deleted} jobs deleted`);
        this.closeCleanupDialog();
        this.loadJobs();
        this.loadStatistics();
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = 'Cleanup failed';
        this.loading = false;
        this.cdr.markForCheck();
        console.error(err);
      }
    });
  }

  deleteJob(taskId: string, event: Event): void {
    event.stopPropagation();

    const dialogData: ConfirmDialogData = {
      title: 'Delete Job History',
      message: `Delete job history for ${taskId}?`,
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
        this.jobHistoryService.deleteJob(taskId).subscribe({
          next: () => {
            this.jobs = this.jobs.filter(j => j.taskId !== taskId);
            if (this.selectedJob?.taskId === taskId) {
              this.selectedJob = null;
              this.viewMode = 'list';
            }
            this.cdr.markForCheck();
            this.loadStatistics();
          },
          error: (err) => {
            this.error = `Failed to delete job ${taskId}`;
            this.cdr.markForCheck();
            console.error(err);
          }
        });
      });
  }

  // ===================== UTILITY METHODS =====================

  formatDuration(ms: number | undefined): string {
    return this.jobHistoryService.formatDuration(ms);
  }

  formatBytes(bytes: number | undefined): string {
    return this.jobHistoryService.formatBytes(bytes);
  }

  formatTimestamp(timestamp: string): string {
    return this.jobHistoryService.formatTimestamp(timestamp);
  }

  getStatusClass(status: JobStatus): string {
    return this.jobHistoryService.getStatusClass(status);
  }

  getStatusIcon(status: JobStatus): string {
    return this.jobHistoryService.getStatusIcon(status);
  }

  getPhaseIcon(phase: IngestPhase | undefined): string {
    return this.jobHistoryService.getPhaseIcon(phase);
  }

  getProgressBarClass(job: IndexingJobHistory): string {
    switch (job.status) {
      case 'COMPLETED': return 'progress-success';
      case 'FAILED': case 'MEMORY_KILLED': return 'progress-error';
      case 'CANCELLED': return 'progress-warning';
      default: return 'progress-info';
    }
  }

  getFailureReasonDisplay(reason: FailureReason | undefined): string {
    if (!reason || reason === 'NONE') return '';
    return reason.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, l => l.toUpperCase());
  }

  // Pagination
  get paginatedJobs(): IndexingJobHistory[] {
    const merged = this.getMergedJobsAndSubprocessEvents();
    const filtered = this.filterJobs(merged);
    const start = this.currentPage * this.pageSize;
    return filtered.slice(start, start + this.pageSize);
  }

  get totalPages(): number {
    const merged = this.getMergedJobsAndSubprocessEvents();
    const filtered = this.filterJobs(merged);
    return Math.ceil(filtered.length / this.pageSize);
  }

  nextPage(): void {
    if (this.currentPage < this.totalPages - 1) {
      this.currentPage++;
      this.cdr.markForCheck();
    }
  }

  prevPage(): void {
    if (this.currentPage > 0) {
      this.currentPage--;
      this.cdr.markForCheck();
    }
  }

  // Track functions
  trackByTaskId(index: number, job: IndexingJobHistory): string {
    return job.taskId;
  }

  // ===================== RESTART EVENT HELPERS =====================

  /**
   * Check if an event type is restart-related.
   */
  isRestartEvent(eventType: string): boolean {
    return ['RESTART_SCHEDULED', 'RESTART_ATTEMPTED', 'RESTART_SUCCEEDED',
            'RESTART_FAILED', 'MEMORY_ANALYSIS', 'HEAP_ADJUSTED',
            'THREADS_REDUCED', 'MANUAL_RESTART'].includes(eventType);
  }

  /**
   * Get CSS class for event type.
   */
  getEventTypeClass(eventType: string): string {
    const baseClass = eventType.toLowerCase().replace(/_/g, '-');

    if (eventType === 'RESTART_SCHEDULED' || eventType === 'RESTART_ATTEMPTED') {
      return `${baseClass} restart-pending`;
    }
    if (eventType === 'RESTART_SUCCEEDED') {
      return `${baseClass} restart-success`;
    }
    if (eventType === 'RESTART_FAILED') {
      return `${baseClass} restart-failed`;
    }
    if (eventType === 'MEMORY_ANALYSIS' || eventType === 'HEAP_ADJUSTED' || eventType === 'THREADS_REDUCED') {
      return `${baseClass} memory-event`;
    }
    return baseClass;
  }

  /**
   * Get icon for restart event type.
   */
  getRestartEventIcon(eventType: string): string {
    switch (eventType) {
      case 'RESTART_SCHEDULED': return 'schedule';
      case 'RESTART_ATTEMPTED': return 'autorenew';
      case 'RESTART_SUCCEEDED': return 'check_circle';
      case 'RESTART_FAILED': return 'error';
      case 'MEMORY_ANALYSIS': return 'memory';
      case 'HEAP_ADJUSTED': return 'tune';
      case 'THREADS_REDUCED': return 'compress';
      case 'MANUAL_RESTART': return 'replay';
      default: return 'info';
    }
  }

  /**
   * Count restart events in the current events list.
   */
  getRestartEventCount(): number {
    return this.events.filter(e => this.isRestartEvent(e.eventType)).length;
  }

  // ===================== SUBPROCESS EVENT HELPERS =====================

  /**
   * Get icon for subprocess event type.
   */
  getSubprocessEventIcon(eventType: SubprocessEventType): string {
    return this.jobHistoryService.getSubprocessEventIcon(eventType);
  }

  /**
   * Get CSS class for subprocess event type.
   */
  getSubprocessEventClass(eventType: SubprocessEventType): string {
    return this.jobHistoryService.getSubprocessEventClass(eventType);
  }

  /**
   * Format subprocess event type for display.
   */
  formatSubprocessEventType(eventType: SubprocessEventType): string {
    return this.jobHistoryService.formatSubprocessEventType(eventType);
  }

  /**
   * Check if the subprocess view is active.
   */
  isSubprocessView(): boolean {
    return this.jobTypeFilter === 'subprocess';
  }

  /**
   * Get filtered subprocess events for display.
   */
  getFilteredSubprocessEvents(): SubprocessEvent[] {
    if (this.searchTerm) {
      const term = this.searchTerm.toLowerCase();
      return this.subprocessEvents.filter(event =>
        event.modelId.toLowerCase().includes(term) ||
        event.eventType.toLowerCase().includes(term) ||
        (event.taskId && event.taskId.toLowerCase().includes(term)) ||
        (event.failureReason && event.failureReason.toLowerCase().includes(term))
      );
    }
    return this.subprocessEvents;
  }

  /**
   * TrackBy function for subprocess events.
   */
  trackBySubprocessEventId(index: number, event: SubprocessEvent): number {
    return event.id;
  }

  /**
   * Get subprocess restart count from statistics.
   */
  getSubprocessRestartCount(): number {
    return this.subprocessStatistics?.totalRestartAttempts || 0;
  }

  /**
   * Get subprocess crash count from statistics.
   */
  getSubprocessCrashCount(): number {
    return this.subprocessStatistics?.totalCrashes || 0;
  }

  /**
   * Get subprocess restart success rate.
   */
  getSubprocessRestartSuccessRate(): number {
    return this.subprocessStatistics?.restartSuccessRate || 0;
  }
}
