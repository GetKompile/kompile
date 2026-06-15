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

import { Component, OnInit, OnDestroy, ViewChild, ElementRef, ChangeDetectionStrategy, ChangeDetectorRef, NgZone } from '@angular/core';
import { trigger, state, style, transition, animate } from '@angular/animations';
import { MatDialog } from '@angular/material/dialog';
import { Subject, timer } from 'rxjs';
import { takeUntil, buffer, debounceTime, filter } from 'rxjs/operators';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import { IngestEventService, RestartInfo } from '../../services/ingest-event.service';
import {
  IngestEvent,
  IngestEventType,
  IngestPhase,
  EventLogSummary,
  EventLogStatusResponse,
  TaskEnvironmentResponse,
  Nd4jEnvironmentConfig,
  getEventSeverity,
  getEventTypeDisplayName,
  getEventTypeIcon,
  getPhaseDisplayName,
  getPhaseIcon
} from '../../models/api-models';

interface TaskGroup {
  taskId: string;
  fileName: string;
  events: IngestEvent[];
  latestEvent: IngestEvent;
  status: 'success' | 'warning' | 'error' | 'info';
  expanded: boolean;
  environment?: TaskEnvironmentResponse;
  loadingEnvironment?: boolean;
}

@Component({
  selector: 'app-ingest-event-viewer',
  standalone: false,
  templateUrl: './ingest-event-viewer.component.html',
  styleUrls: ['./ingest-event-viewer.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [
    trigger('expandCollapse', [
      state('void', style({
        height: '0',
        opacity: '0',
        overflow: 'hidden'
      })),
      state('*', style({
        height: '*',
        opacity: '1',
        overflow: 'hidden'
      })),
      transition('void <=> *', [
        animate('200ms ease-in-out')
      ])
    ])
  ]
})
export class IngestEventViewerComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  // Data
  events: IngestEvent[] = [];
  taskGroups: TaskGroup[] = [];
  summary: EventLogSummary | null = null;
  status: EventLogStatusResponse | null = null;

  // UI State
  loading = false;
  error: string | null = null;
  selectedTaskId: string | null = null;
  viewMode: 'live' | 'timeline' | 'grouped' | 'table' = 'live'; // Default to live view
  autoRefresh = true;
  refreshInterval = 5; // seconds
  wsConnected = false;

  // Live log view data
  liveEvents: IngestEvent[] = [];
  maxLiveEvents = 200; // Keep last 200 events in live view

  // Filters
  lookbackHours = 24;
  filterEventTypes: IngestEventType[] = [];
  filterPhases: IngestPhase[] = [];
  showErrorsOnly = false;
  searchTerm = '';

  // Cleanup dialog
  showCleanupDialog = false;
  cleanupDays = 7;

  // Pagination
  pageSize = 50;
  currentPage = 0;

  // Available filter options
  eventTypeOptions: IngestEventType[] = [
    'QUEUED', 'PHASE_STARTED', 'PROGRESS', 'PHASE_COMPLETED',
    'STATE_TRANSITION', 'WARNING', 'ERROR', 'COMPLETED', 'FAILED', 'CANCELLED',
    // Restart-related event types
    'RESTART_SCHEDULED', 'RESTART_ATTEMPTED', 'RESTART_SUCCEEDED',
    'RESTART_FAILED', 'MEMORY_ANALYSIS', 'HEAP_ADJUSTED', 'THREADS_REDUCED', 'MANUAL_RESTART'
  ];

  // Active restart tracking
  activeRestarts: Map<string, RestartInfo> = new Map();

  phaseOptions: IngestPhase[] = [
    IngestPhase.QUEUED, IngestPhase.UPLOADING, IngestPhase.LOADING, IngestPhase.OCR_PROCESSING,
    IngestPhase.CONVERTING, IngestPhase.CHUNKING,
    IngestPhase.EMBEDDING, IngestPhase.INDEXING, IngestPhase.COMPLETED, IngestPhase.FAILED
  ];

  @ViewChild('eventLogContainer') eventLogContainer!: ElementRef;
  @ViewChild('liveLogContent') liveLogContent!: ElementRef;

  // Event buffering for performance
  private eventBuffer$ = new Subject<IngestEvent>();
  private scrollDebounce$ = new Subject<void>();
  private pendingScroll = false;

  constructor(
    private eventService: IngestEventService,
    private cdr: ChangeDetectorRef,
    private ngZone: NgZone,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadStatus();
    this.setupSubscriptions();
    this.setupEventBuffering();

    if (this.autoRefresh) {
      this.startAutoRefresh();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.eventService.stopPolling();
  }

  /**
   * Set up event buffering for performance.
   * Events are batched every 100ms to prevent UI lockup.
   */
  private setupEventBuffering(): void {
    this.ngZone.runOutsideAngular(() => {
      // Buffer events and process in batches
      const batchTrigger$ = timer(100, 100);
      this.eventBuffer$.pipe(
        buffer(batchTrigger$),
        takeUntil(this.destroy$)
      ).subscribe(batch => {
        if (batch.length > 0) {
          this.ngZone.run(() => {
            this.processEventBatch(batch);
          });
        }
      });

      // Debounce scroll requests
      this.scrollDebounce$.pipe(
        debounceTime(100),
        takeUntil(this.destroy$)
      ).subscribe(() => {
        this.ngZone.run(() => {
          this.scrollLiveLogToBottomInternal();
        });
      });
    });
  }

  /**
   * Process a batch of events at once.
   */
  private processEventBatch(batch: IngestEvent[]): void {
    for (const event of batch) {
      this.liveEvents.push(event);
    }

    // Keep only the last maxLiveEvents events
    if (this.liveEvents.length > this.maxLiveEvents) {
      this.liveEvents = this.liveEvents.slice(-this.maxLiveEvents);
    }

    // Request scroll (debounced)
    if (this.viewMode === 'live') {
      this.scrollDebounce$.next();
    }

    this.cdr.markForCheck();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // DATA LOADING
  // ═══════════════════════════════════════════════════════════════════════════════

  private setupSubscriptions(): void {
    // Subscribe to events stream
    this.eventService.events$
      .pipe(takeUntil(this.destroy$))
      .subscribe(events => {
        this.events = events;
        this.updateTaskGroups();
        this.cdr.markForCheck();
      });

    // Subscribe to summary stream
    this.eventService.summary$
      .pipe(takeUntil(this.destroy$))
      .subscribe(summary => {
        this.summary = summary;
        this.cdr.markForCheck();
      });

    // Subscribe to status stream
    this.eventService.status$
      .pipe(takeUntil(this.destroy$))
      .subscribe(status => {
        this.status = status;
        this.cdr.markForCheck();
      });

    // Subscribe to new events for notifications
    this.eventService.newEvent$
      .pipe(takeUntil(this.destroy$))
      .subscribe(event => {
        this.onNewEvent(event);
      });

    // Subscribe to WebSocket connection status
    this.eventService.wsConnected$
      .pipe(takeUntil(this.destroy$))
      .subscribe(connected => {
        this.wsConnected = connected;
        this.cdr.markForCheck();
      });

    // Subscribe to active restarts
    this.eventService.activeRestarts$
      .pipe(takeUntil(this.destroy$))
      .subscribe(restarts => {
        this.activeRestarts = restarts;
        this.cdr.markForCheck();
      });
  }

  loadStatus(): void {
    this.eventService.getStatus().subscribe({
      next: (status) => {
        this.status = status;
        if (status.enabled) {
          this.loadEvents();
          this.loadSummary();
        }
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = 'Failed to load event log status';
        console.error(err);
        this.cdr.markForCheck();
      }
    });
  }

  loadEvents(): void {
    this.loading = true;
    this.error = null;
    this.cdr.markForCheck();

    if (this.showErrorsOnly) {
      this.eventService.getErrorEvents(this.lookbackHours).subscribe({
        next: (response) => {
          this.events = response.errors;
          this.updateTaskGroups();
          this.updateLiveEvents();
          this.loading = false;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          this.error = 'Failed to load events';
          this.loading = false;
          console.error(err);
          this.cdr.markForCheck();
        }
      });
    } else {
      this.eventService.getRecentEvents(this.lookbackHours).subscribe({
        next: (response) => {
          this.events = response.events;
          this.updateTaskGroups();
          this.updateLiveEvents();
          this.loading = false;
          this.cdr.markForCheck();
        },
        error: (err: Error) => {
          this.error = 'Failed to load events';
          this.loading = false;
          console.error(err);
          this.cdr.markForCheck();
        }
      });
    }
  }

  private updateLiveEvents(): void {
    // Populate live events from loaded events, keeping the most recent ones
    if (this.events.length > 0) {
      const sortedEvents = [...this.events].sort((a, b) =>
        new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime()
      );
      this.liveEvents = sortedEvents.slice(-this.maxLiveEvents);

      // Scroll to bottom after populating
      if (this.viewMode === 'live') {
        setTimeout(() => this.scrollLiveLogToBottom(), 100);
      }
    }
  }

  loadSummary(): void {
    this.eventService.getSummary(this.lookbackHours).subscribe({
      next: (summary) => {
        this.summary = summary;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load summary:', err);
      }
    });
  }

  loadTaskEvents(taskId: string): void {
    this.selectedTaskId = taskId;
    this.loading = true;
    this.cdr.markForCheck();

    const group = this.taskGroups.find(g => g.taskId === taskId);

    this.eventService.getEventsForTask(taskId).subscribe({
      next: (response) => {
        // Update the task group with full events
        if (group) {
          group.events = response.events;
          group.expanded = true;
        }
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = `Failed to load events for task ${taskId}`;
        this.loading = false;
        console.error(err);
        this.cdr.markForCheck();
      }
    });

    // Also load the environment snapshot
    if (group) {
      group.loadingEnvironment = true;
      this.eventService.getTaskEnvironment(taskId).subscribe({
        next: (envResponse) => {
          if (group) {
            group.environment = envResponse;
            group.loadingEnvironment = false;
          }
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error(`Failed to load environment for task ${taskId}:`, err);
          if (group) {
            group.loadingEnvironment = false;
          }
          this.cdr.markForCheck();
        }
      });
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // DATA PROCESSING
  // ═══════════════════════════════════════════════════════════════════════════════

  private updateTaskGroups(): void {
    const grouped = this.eventService.groupEventsByTask(this.getFilteredEvents());
    this.taskGroups = [];

    grouped.forEach((events, taskId) => {
      const sortedEvents = this.eventService.sortEventsByTimestamp(events, true);
      const latestEvent = sortedEvents[sortedEvents.length - 1];

      this.taskGroups.push({
        taskId,
        fileName: latestEvent?.fileName || 'Unknown',
        events: sortedEvents,
        latestEvent,
        status: this.getTaskStatus(latestEvent),
        expanded: this.selectedTaskId === taskId
      });
    });

    // Sort by latest event timestamp (newest first)
    this.taskGroups.sort((a, b) => {
      const timeA = new Date(a.latestEvent?.timestamp || 0).getTime();
      const timeB = new Date(b.latestEvent?.timestamp || 0).getTime();
      return timeB - timeA;
    });
  }

  private getTaskStatus(event: IngestEvent | undefined): 'success' | 'warning' | 'error' | 'info' {
    if (!event) return 'info';
    const severity = getEventSeverity(event.eventType);
    return severity;
  }

  getFilteredEvents(): IngestEvent[] {
    let filtered = [...this.events];

    // Filter by event types
    if (this.filterEventTypes.length > 0) {
      filtered = filtered.filter(e => this.filterEventTypes.includes(e.eventType));
    }

    // Filter by phases
    if (this.filterPhases.length > 0) {
      filtered = filtered.filter(e => e.phase && this.filterPhases.includes(e.phase));
    }

    // Filter by search term
    if (this.searchTerm) {
      const term = this.searchTerm.toLowerCase();
      filtered = filtered.filter(e =>
        e.fileName.toLowerCase().includes(term) ||
        e.taskId.toLowerCase().includes(term) ||
        e.message.toLowerCase().includes(term)
      );
    }

    return filtered;
  }

  getPaginatedEvents(): IngestEvent[] {
    const filtered = this.getFilteredEvents();
    const start = this.currentPage * this.pageSize;
    return filtered.slice(start, start + this.pageSize);
  }

  getTotalPages(): number {
    return Math.ceil(this.getFilteredEvents().length / this.pageSize);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // AUTO REFRESH
  // ═══════════════════════════════════════════════════════════════════════════════

  startAutoRefresh(): void {
    this.eventService.startPolling(this.refreshInterval * 1000, this.lookbackHours);
  }

  stopAutoRefresh(): void {
    this.eventService.stopPolling();
  }

  toggleAutoRefresh(): void {
    this.autoRefresh = !this.autoRefresh;
    if (this.autoRefresh) {
      this.startAutoRefresh();
    } else {
      this.stopAutoRefresh();
    }
  }

  onRefreshIntervalChange(): void {
    if (this.autoRefresh) {
      this.stopAutoRefresh();
      this.startAutoRefresh();
    }
  }

  manualRefresh(): void {
    this.loadEvents();
    this.loadSummary();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // EVENT HANDLERS
  // ═══════════════════════════════════════════════════════════════════════════════

  private onNewEvent(event: IngestEvent): void {
    // Buffer the event for batch processing
    this.eventBuffer$.next(event);
  }

  private scrollLiveLogToBottom(): void {
    // Use debounced scroll
    this.scrollDebounce$.next();
  }

  private scrollLiveLogToBottomInternal(): void {
    if (this.liveLogContent?.nativeElement) {
      const el = this.liveLogContent.nativeElement;
      el.scrollTop = el.scrollHeight;
    }
  }

  onTaskClick(group: TaskGroup): void {
    if (group.expanded) {
      group.expanded = false;
      this.selectedTaskId = null;
    } else {
      this.loadTaskEvents(group.taskId);
    }
  }

  onViewModeChange(mode: 'live' | 'timeline' | 'grouped' | 'table'): void {
    this.viewMode = mode;
    if (mode === 'live') {
      // Scroll to bottom when switching to live view
      setTimeout(() => this.scrollLiveLogToBottom(), 100);
    }
  }

  onLookbackChange(): void {
    this.loadEvents();
    this.loadSummary();
    if (this.autoRefresh) {
      this.stopAutoRefresh();
      this.startAutoRefresh();
    }
  }

  onFilterChange(): void {
    this.currentPage = 0;
    this.updateTaskGroups();
  }

  clearFilters(): void {
    this.filterEventTypes = [];
    this.filterPhases = [];
    this.searchTerm = '';
    this.showErrorsOnly = false;
    this.onFilterChange();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CLEANUP OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  openCleanupDialog(): void {
    this.showCleanupDialog = true;
  }

  closeCleanupDialog(): void {
    this.showCleanupDialog = false;
  }

  performCleanup(): void {
    this.loading = true;
    this.cdr.markForCheck();
    this.eventService.forceCleanup(this.cleanupDays).subscribe({
      next: (response) => {
        console.log(`Cleanup complete: ${response.deletedCount} events deleted`);
        this.closeCleanupDialog();
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = 'Cleanup failed';
        this.loading = false;
        console.error(err);
        this.cdr.markForCheck();
      }
    });
  }

  deleteTaskEvents(taskId: string, event: Event): void {
    event.stopPropagation();

    const dialogData: ConfirmDialogData = {
      title: 'Delete Task Events',
      message: `Delete all events for task ${taskId}?`,
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
        this.eventService.deleteTaskEvents(taskId).subscribe({
          next: () => {
            this.taskGroups = this.taskGroups.filter(g => g.taskId !== taskId);
            if (this.selectedTaskId === taskId) {
              this.selectedTaskId = null;
            }
            this.cdr.markForCheck();
          },
          error: (err) => {
            this.error = `Failed to delete events for task ${taskId}`;
            console.error(err);
            this.cdr.markForCheck();
          }
        });
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // UTILITY METHODS (exposed for template)
  // ═══════════════════════════════════════════════════════════════════════════════

  getEventSeverity = getEventSeverity;
  getEventTypeDisplayName = getEventTypeDisplayName;
  getEventTypeIcon = getEventTypeIcon;
  getPhaseDisplayName = getPhaseDisplayName;
  getPhaseIcon = getPhaseIcon;

  formatDuration(ms: number | undefined): string {
    return this.eventService.formatDuration(ms);
  }

  formatTimestamp(timestamp: string): string {
    return this.eventService.formatTimestamp(timestamp);
  }

  getRelativeTime(timestamp: string): string {
    return this.eventService.getRelativeTime(timestamp);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'success': return 'status-success';
      case 'warning': return 'status-warning';
      case 'error': return 'status-error';
      default: return 'status-info';
    }
  }

  getSeverityClass(eventType: IngestEventType): string {
    const severity = getEventSeverity(eventType);
    return `severity-${severity}`;
  }

  isEventTypeSelected(type: IngestEventType): boolean {
    return this.filterEventTypes.includes(type);
  }

  toggleEventTypeFilter(type: IngestEventType): void {
    const index = this.filterEventTypes.indexOf(type);
    if (index >= 0) {
      this.filterEventTypes.splice(index, 1);
    } else {
      this.filterEventTypes.push(type);
    }
    this.onFilterChange();
  }

  isPhaseSelected(phase: IngestPhase): boolean {
    return this.filterPhases.includes(phase);
  }

  togglePhaseFilter(phase: IngestPhase): void {
    const index = this.filterPhases.indexOf(phase);
    if (index >= 0) {
      this.filterPhases.splice(index, 1);
    } else {
      this.filterPhases.push(phase);
    }
    this.onFilterChange();
  }

  trackByTaskId(index: number, group: TaskGroup): string {
    return group.taskId;
  }

  trackByEventId(index: number, event: IngestEvent): number | string {
    return event.id || `${event.taskId}-${event.timestamp}`;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // LIVE LOG VIEW METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  getCurrentStateClass(): string {
    const activeTasks = this.getActiveTasks();
    const hasErrors = this.getErrorEvents().length > 0;

    if (!this.wsConnected) {
      return 'state-idle';
    }
    if (activeTasks.length > 0) {
      return 'state-processing';
    }
    if (hasErrors) {
      return 'state-failed';
    }
    if (this.summary && this.summary.completed > 0) {
      return 'state-success';
    }
    return 'state-idle';
  }

  getCurrentStateIcon(): string {
    const activeTasks = this.getActiveTasks();
    const hasErrors = this.getErrorEvents().length > 0;

    if (!this.wsConnected) {
      return 'wifi_off';
    }
    if (activeTasks.length > 0) {
      return 'sync';
    }
    if (hasErrors) {
      return 'error_outline';
    }
    if (this.summary && this.summary.completed > 0) {
      return 'check_circle';
    }
    return 'hourglass_empty';
  }

  getCurrentStateText(): string {
    const activeTasks = this.getActiveTasks();
    const hasRecentErrors = this.getErrorEvents().length > 0;

    if (!this.wsConnected) {
      return 'WebSocket Disconnected - Using HTTP Polling';
    }
    if (activeTasks.length > 0) {
      return 'Processing Documents';
    }
    if (hasRecentErrors) {
      return 'Errors Detected';
    }
    if (this.summary && this.summary.completed > 0) {
      return 'Ready - All Tasks Completed';
    }
    return 'Idle - Waiting for Documents';
  }

  getActiveTaskCount(): number {
    return this.getActiveTasks().length;
  }

  getActiveTasks(): TaskGroup[] {
    // Return task groups that are still in progress (not completed/failed/cancelled)
    return this.taskGroups.filter(group => {
      const eventType = group.latestEvent?.eventType;
      return eventType !== 'COMPLETED' && eventType !== 'FAILED' && eventType !== 'CANCELLED';
    });
  }

  getErrorEvents(): IngestEvent[] {
    // Return recent error and warning events from live stream
    return this.liveEvents.filter(event =>
      event.eventType === 'ERROR' || event.eventType === 'FAILED' || event.eventType === 'WARNING'
    );
  }

  getLogLineClass(event: IngestEvent): string {
    switch (event.eventType) {
      case 'ERROR':
      case 'FAILED':
        return 'log-error';
      case 'WARNING':
        return 'log-warning';
      case 'COMPLETED':
        return 'log-success';
      case 'PROGRESS':
      case 'PHASE_STARTED':
      case 'PHASE_COMPLETED':
      case 'STATE_TRANSITION':
        return 'log-info';
      default:
        return '';
    }
  }

  formatLogTimestamp(timestamp: string): string {
    if (!timestamp) return '';
    try {
      const date = new Date(timestamp);
      return date.toLocaleTimeString('en-US', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false
      });
    } catch {
      return timestamp;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // RESTART-RELATED METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Check if event is a restart-related event.
   */
  isRestartEvent(event: IngestEvent): boolean {
    return this.eventService.isRestartEvent(event.eventType);
  }

  /**
   * Check if event is a memory-related event.
   */
  isMemoryEvent(event: IngestEvent): boolean {
    return this.eventService.isMemoryEvent(event.eventType);
  }

  /**
   * Get restart-related events from live events.
   */
  getRestartEvents(): IngestEvent[] {
    return this.liveEvents.filter(event => this.isRestartEvent(event));
  }

  /**
   * Trigger manual restart for a task.
   */
  manualRestart(taskId: string, event?: Event): void {
    if (event) {
      event.stopPropagation();
    }

    const dialogData: ConfirmDialogData = {
      title: 'Restart Task',
      message: `Restart the failed task ${taskId}? This will launch a new subprocess.`,
      confirmText: 'Restart',
      confirmColor: 'primary',
      icon: 'refresh'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.eventService.manualRestart(taskId).subscribe({
          next: () => {
            console.log(`Manual restart initiated for task ${taskId}`);
          },
          error: (err) => {
            this.error = `Failed to restart task: ${err.message || 'Unknown error'}`;
            console.error(err);
            this.cdr.markForCheck();
          }
        });
      });
  }

  /**
   * Get restart info for a task.
   */
  getRestartInfo(taskId: string): RestartInfo | undefined {
    return this.activeRestarts.get(taskId);
  }

  /**
   * Check if a task has an active restart.
   */
  hasActiveRestart(taskId: string): boolean {
    return this.activeRestarts.has(taskId);
  }

  /**
   * Get countdown text for scheduled restart.
   */
  getRestartCountdown(event: IngestEvent): string {
    if (!event.nextRestartTime) {
      return event.backoffMs ? `${Math.round(event.backoffMs / 1000)}s` : 'soon';
    }

    const scheduledTime = new Date(event.nextRestartTime).getTime();
    const now = Date.now();
    const remaining = Math.max(0, scheduledTime - now);

    if (remaining < 1000) return 'now';
    if (remaining < 60000) return `${Math.ceil(remaining / 1000)}s`;
    return `${Math.ceil(remaining / 60000)}m`;
  }

  /**
   * Format bytes to human-readable string.
   */
  formatBytesToHuman(bytes: number | undefined): string {
    if (!bytes) return 'N/A';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  /**
   * Get CSS class for restart event type.
   */
  getRestartEventClass(event: IngestEvent): string {
    switch (event.eventType) {
      case 'RESTART_SCHEDULED':
        return 'log-restart-scheduled';
      case 'RESTART_ATTEMPTED':
        return 'log-restart-attempted';
      case 'RESTART_SUCCEEDED':
        return 'log-restart-succeeded';
      case 'RESTART_FAILED':
        return 'log-restart-failed';
      case 'MEMORY_ANALYSIS':
        return 'log-memory-analysis';
      case 'HEAP_ADJUSTED':
        return 'log-heap-adjusted';
      case 'THREADS_REDUCED':
        return 'log-threads-reduced';
      case 'MANUAL_RESTART':
        return 'log-manual-restart';
      default:
        return '';
    }
  }

  /**
   * Enhanced log line class that includes restart events.
   */
  getLogLineClassEnhanced(event: IngestEvent): string {
    // Check for restart events first
    const restartClass = this.getRestartEventClass(event);
    if (restartClass) {
      return restartClass;
    }

    // Fall back to original logic
    return this.getLogLineClass(event);
  }

  /**
   * Check if task can be manually restarted.
   */
  canManuallyRestart(event: IngestEvent): boolean {
    return (event.eventType === 'FAILED' || event.eventType === 'RESTART_FAILED') &&
           !this.hasActiveRestart(event.taskId);
  }

  /**
   * Get memory analysis details for display.
   */
  getMemoryDetails(event: IngestEvent): {
    systemRamTotal: string;
    systemRamFree: string;
    heapSize: string;
    heapIncreased: boolean;
    ompThreads: number | undefined;
    blasThreads: number | undefined;
    reason: string;
  } | null {
    if (!this.isMemoryEvent(event) && event.eventType !== 'RESTART_SCHEDULED' && event.eventType !== 'RESTART_ATTEMPTED') {
      return null;
    }

    return {
      systemRamTotal: this.formatBytesToHuman(event.systemRamTotal),
      systemRamFree: this.formatBytesToHuman(event.systemRamFree),
      heapSize: event.heapSize || 'N/A',
      heapIncreased: event.heapIncreased || false,
      ompThreads: event.ompThreads,
      blasThreads: event.blasThreads,
      reason: event.memoryAnalysisReason || event.message || ''
    };
  }

  /**
   * Get the count of restart events for a task group.
   */
  getRestartAttemptCount(group: TaskGroup): number {
    if (!group || !group.events) return 0;
    return group.events.filter(e =>
      e.eventType === 'RESTART_SCHEDULED' ||
      e.eventType === 'RESTART_ATTEMPTED' ||
      e.eventType === 'RESTART_SUCCEEDED' ||
      e.eventType === 'RESTART_FAILED'
    ).length;
  }

  /**
   * Check if any events in the group are restart-related.
   */
  hasRestartEvents(group: TaskGroup): boolean {
    return this.getRestartAttemptCount(group) > 0;
  }
}
