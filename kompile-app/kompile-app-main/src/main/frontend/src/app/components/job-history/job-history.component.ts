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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { Subject, interval } from 'rxjs';
import { takeUntil, filter } from 'rxjs/operators';
import {
  JobHistoryService,
  IndexingJobHistory,
  JobStatistics,
  JobStatus,
  FailureReason,
  IngestPhase,
  JobSummary,
  IngestEvent
} from '../../services/job-history.service';
import { WebSocketService, WebSocketConnectionState } from '../../services/websocket.service';
import { IngestProgressUpdate, IngestStatus } from '../../models/api-models';

@Component({
  selector: 'app-job-history',
  standalone: false,
  templateUrl: './job-history.component.html',
  styleUrls: ['./job-history.component.css']
})
export class JobHistoryComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();
  private wsSubscribed = false;

  // Data
  jobs: IndexingJobHistory[] = [];
  statistics: JobStatistics | null = null;
  selectedJob: JobSummary | null = null;
  events: IngestEvent[] = [];
  parsedNd4jEnvironment: any = null;
  showNd4jDetails = false;

  // WebSocket state
  wsConnected = false;

  // UI State
  loading = false;
  error: string | null = null;
  viewMode: 'list' | 'stats' | 'detail' = 'list';
  autoRefresh = true;
  refreshIntervalSeconds = 30;

  // Filters
  lookbackHours = 24;
  statusFilter: JobStatus | 'ALL' = 'ALL';
  jobTypeFilter: 'ALL' | 'ingest' | 'vector-population' = 'ALL';
  searchTerm = '';

  // Pagination
  currentPage = 0;
  pageSize = 20;
  totalJobs = 0;

  // Cleanup dialog
  showCleanupDialog = false;
  cleanupDays = 30;

  // Available status options
  statusOptions: (JobStatus | 'ALL')[] = ['ALL', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'MEMORY_KILLED'];

  constructor(
    private jobHistoryService: JobHistoryService,
    private webSocketService: WebSocketService
  ) { }

  ngOnInit(): void {
    this.loadJobs();
    this.loadStatistics();

    // Connect to WebSocket and subscribe to all task updates
    this.initWebSocket();

    if (this.autoRefresh) {
      this.startAutoRefresh();
    }
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
   * Updates the job in the list or reloads if not found.
   */
  private handleProgressUpdate(update: IngestProgressUpdate, contentType?: string): void {
    const jobIndex = this.jobs.findIndex(j => j.taskId === update.taskId);

    if (jobIndex >= 0) {
      // Update existing job in-place
      const job = this.jobs[jobIndex];
      job.progressPercent = update.progressPercent;
      job.lastPhase = update.phase as IngestPhase;

      // Update status based on ingest status
      if (update.status === IngestStatus.COMPLETED) {
        job.status = 'COMPLETED';
      } else if (update.status === IngestStatus.FAILED) {
        job.status = 'FAILED';
      } else if (update.status === IngestStatus.CANCELLED) {
        job.status = 'CANCELLED';
      } else if (update.status === IngestStatus.IN_PROGRESS) {
        job.status = 'RUNNING';
      }

      // Update stats if available
      if (update.stats) {
        job.documentsLoaded = update.stats.documentsLoaded;
        job.chunksCreated = update.stats.chunksCreated;
        job.chunksEmbedded = update.stats.chunksEmbedded;
        job.documentsIndexed = update.stats.documentsIndexed;
      }

      // Trigger change detection by creating new array reference
      this.jobs = [...this.jobs];

      // Reload statistics when a job completes or fails
      if (update.status === IngestStatus.COMPLETED || update.status === IngestStatus.FAILED) {
        this.loadStatistics();
      }
    } else {
      // Job not in list - might be new, reload the list
      console.log('JobHistory: New task detected, reloading jobs list');
      this.loadJobs();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ===================== DATA LOADING =====================

  loadJobs(): void {
    this.loading = true;
    this.error = null;

    if (this.statusFilter === 'ALL') {
      this.jobHistoryService.getRecentJobs(this.lookbackHours).subscribe({
        next: (jobs) => {
          this.jobs = this.filterJobs(jobs);
          this.totalJobs = jobs.length;
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load job history';
          this.loading = false;
          console.error(err);
        }
      });
    } else {
      this.jobHistoryService.getJobsByStatus(this.statusFilter).subscribe({
        next: (jobs) => {
          this.jobs = this.filterJobs(jobs);
          this.totalJobs = jobs.length;
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load job history';
          this.loading = false;
          console.error(err);
        }
      });
    }
  }

  loadStatistics(): void {
    this.jobHistoryService.getStatistics(this.lookbackHours).subscribe({
      next: (stats) => {
        this.statistics = stats;
      },
      error: (err) => {
        console.error('Failed to load statistics:', err);
      }
    });
  }

  loadEvents(taskId: string): void {
    this.jobHistoryService.getTaskEvents(taskId).subscribe({
      next: (response) => {
        this.events = response.events || [];
      },
      error: (err) => {
        console.error('Failed to load events:', err);
        this.events = [];
      }
    });
  }

  loadJobDetail(taskId: string): void {
    this.loading = true;
    this.parsedNd4jEnvironment = null;
    this.showNd4jDetails = false;
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
        // Load events for this job
        this.loadEvents(taskId);
      },
      error: (err) => {
        this.error = `Failed to load job details for ${taskId}`;
        this.loading = false;
        console.error(err);
      }
    });
  }

  // ===================== FILTERING =====================

  filterJobs(jobs: IndexingJobHistory[]): IndexingJobHistory[] {
    let filtered = jobs;

    // Apply job type filter
    if (this.jobTypeFilter !== 'ALL') {
      filtered = filtered.filter(job => {
        if (this.jobTypeFilter === 'vector-population') {
          return job.contentType === 'vector-population';
        } else {
          return job.contentType !== 'vector-population';
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

  onJobTypeFilterChange(): void {
    this.currentPage = 0;
    this.loadJobs();
  }

  /**
   * Get display name for job type
   */
  getJobTypeDisplay(contentType?: string): string {
    return contentType === 'vector-population' ? 'Vector Population' : 'Document Ingest';
  }

  /**
   * Get icon for job type
   */
  getJobTypeIcon(contentType?: string): string {
    return contentType === 'vector-population' ? 'storage' : 'upload_file';
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
        }
        this.loadStatistics();
      });
  }

  toggleAutoRefresh(): void {
    this.autoRefresh = !this.autoRefresh;
    if (this.autoRefresh) {
      this.startAutoRefresh();
    }
  }

  manualRefresh(): void {
    this.loadJobs();
    this.loadStatistics();
  }

  // ===================== VIEW MODES =====================

  showList(): void {
    this.viewMode = 'list';
    this.selectedJob = null;
    this.parsedNd4jEnvironment = null;
    this.showNd4jDetails = false;
    this.loadJobs();
  }

  toggleNd4jDetails(): void {
    this.showNd4jDetails = !this.showNd4jDetails;
  }

  getNd4jConfigCategories(): { name: string; icon: string; fields: { key: string; value: any }[] }[] {
    if (!this.parsedNd4jEnvironment) return [];

    const env = this.parsedNd4jEnvironment;
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

    return categories;
  }

  showStats(): void {
    this.viewMode = 'stats';
    this.loadStatistics();
  }

  showDetail(job: IndexingJobHistory): void {
    this.loadJobDetail(job.taskId);
  }

  // ===================== CLEANUP =====================

  openCleanupDialog(): void {
    this.showCleanupDialog = true;
  }

  closeCleanupDialog(): void {
    this.showCleanupDialog = false;
  }

  performCleanup(): void {
    this.loading = true;
    this.jobHistoryService.forceCleanup(this.cleanupDays).subscribe({
      next: (response) => {
        console.log(`Cleanup complete: ${response.deleted} jobs deleted`);
        this.closeCleanupDialog();
        this.loadJobs();
        this.loadStatistics();
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Cleanup failed';
        this.loading = false;
        console.error(err);
      }
    });
  }

  deleteJob(taskId: string, event: Event): void {
    event.stopPropagation();
    if (confirm(`Delete job history for ${taskId}?`)) {
      this.jobHistoryService.deleteJob(taskId).subscribe({
        next: () => {
          this.jobs = this.jobs.filter(j => j.taskId !== taskId);
          if (this.selectedJob?.taskId === taskId) {
            this.selectedJob = null;
            this.viewMode = 'list';
          }
          this.loadStatistics();
        },
        error: (err) => {
          this.error = `Failed to delete job ${taskId}`;
          console.error(err);
        }
      });
    }
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
    const start = this.currentPage * this.pageSize;
    return this.jobs.slice(start, start + this.pageSize);
  }

  get totalPages(): number {
    return Math.ceil(this.jobs.length / this.pageSize);
  }

  nextPage(): void {
    if (this.currentPage < this.totalPages - 1) {
      this.currentPage++;
    }
  }

  prevPage(): void {
    if (this.currentPage > 0) {
      this.currentPage--;
    }
  }

  // Track functions
  trackByTaskId(index: number, job: IndexingJobHistory): string {
    return job.taskId;
  }
}
