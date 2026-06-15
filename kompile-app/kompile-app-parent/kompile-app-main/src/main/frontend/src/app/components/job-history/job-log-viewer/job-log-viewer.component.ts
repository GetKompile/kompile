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

import { Component, Input, OnInit, OnDestroy, OnChanges, SimpleChanges, ElementRef, ViewChild, ChangeDetectorRef, NgZone, ChangeDetectionStrategy, AfterViewInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatBadgeModule } from '@angular/material/badge';
import { Subject, Subscription, timer, takeUntil, debounceTime, distinctUntilChanged, buffer, switchMap, of, catchError, tap } from 'rxjs';

import { JobLogService, JobLogEntry, LogLevel, JobLogsResponse, ArchivedLogEntry } from '../../../services/job-log.service';
import { WebSocketService } from '../../../services/websocket.service';
import { IngestLogEntry } from '../../../models/api-models';

@Component({
  selector: 'app-job-log-viewer',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatFormFieldModule,
    MatInputModule,
    MatTooltipModule,
    MatSlideToggleModule,
    MatBadgeModule
  ],
  templateUrl: './job-log-viewer.component.html',
  styleUrls: ['./job-log-viewer.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class JobLogViewerComponent implements OnInit, OnDestroy, OnChanges, AfterViewInit {
  @Input() taskId: string = '';
  @Input() isJobRunning: boolean = false;  // Whether to use real-time streaming
  @Input() logSource: 'ingest' | 'vector-population' = 'ingest';  // Type of log source
  @Input() maxTailLogs: number = 200;  // Max logs to keep in tail mode (circular buffer)
  @Input() maxArchiveLogs: number = 5000;  // Max logs to load from archive (prevent memory issues)
  @ViewChild('logContainer') logContainer!: ElementRef;
  @ViewChild('searchInput') searchInputRef!: ElementRef<HTMLInputElement>;

  logs: JobLogEntry[] = [];
  totalCount = 0;
  levelCounts: { [key in LogLevel]?: number } = {};

  // Search loading state (separate from main loading)
  searchLoading = false;

  // Filtering
  selectedLevels: Set<LogLevel> = new Set<LogLevel>(['INFO', 'WARN', 'ERROR']);
  searchText = '';
  showTimestamps = true;
  autoScroll = true;

  // Tail mode - real-time streaming with circular buffer
  tailMode = false;
  streamingActive = false;

  // Pagination (for non-tail mode)
  currentPage = 0;
  pageSize = 100;  // Reduced from 500 for better rendering performance
  pageInput: number = 1;  // User input for direct page navigation (1-indexed for display)
  private initialLoadComplete = false;  // Track if initial load is done

  // State
  loading = false;
  error: string | null = null;
  enabled = true;
  loadedFromArchive = false;  // Indicates logs were loaded from archive

  private destroy$ = new Subject<void>();
  private searchSubject = new Subject<string>();

  // WebSocket streaming
  private wsSubscription: Subscription | null = null;
  private logBuffer$ = new Subject<IngestLogEntry>();
  private batchSubscription: Subscription | null = null;
  private lastSequenceNumber = 0;

  // Native event listener for search input (bypasses Angular change detection)
  private searchInputListener: ((e: Event) => void) | null = null;

  readonly allLevels: LogLevel[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'];

  constructor(
    private jobLogService: JobLogService,
    private webSocketService: WebSocketService,
    private cdr: ChangeDetectorRef,
    private ngZone: NgZone
  ) { }

  ngOnInit(): void {
    // Setup debounced search - runs outside Angular zone for performance
    // Only enters Angular zone when actually loading data
    this.searchSubject.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      takeUntil(this.destroy$)
    ).subscribe(search => {
      // Enter Angular zone only when we need to update UI and load data
      this.ngZone.run(() => {
        this.searchText = search;
        this.currentPage = 0;
        if (!this.tailMode) {
          this.searchLoading = true;
          this.cdr.markForCheck();
          this.loadLogs();
        } else {
          // In tail mode, re-filter without reloading
          this.searchLoading = false;
          this.refilterTailLogs();
        }
      });
    });

    // Setup log batching for WebSocket streaming
    this.setupLogBatching();

    // Initial load if taskId is provided
    if (this.taskId) {
      // If job is running, start in tail mode with WebSocket streaming
      if (this.isJobRunning) {
        this.enableTailMode();
      } else {
        // Start from last page for completed jobs
        this.loadLogs(true);
      }
    }
  }

  ngAfterViewInit(): void {
    // Attach native event listener to search input to completely bypass Angular change detection
    // This is critical for performance when there are many log entries rendered
    if (this.searchInputRef?.nativeElement) {
      this.searchInputListener = (e: Event) => {
        const value = (e.target as HTMLInputElement).value;
        this.searchSubject.next(value);
      };
      // Run outside Angular zone to prevent any change detection
      this.ngZone.runOutsideAngular(() => {
        this.searchInputRef.nativeElement.addEventListener('input', this.searchInputListener!);
      });
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['taskId'] && !changes['taskId'].firstChange) {
      this.currentPage = 0;
      this.lastSequenceNumber = 0;
      this.initialLoadComplete = false;
      this.stopStreaming();

      if (this.isJobRunning) {
        this.enableTailMode();
      } else {
        this.tailMode = false;
        // Start from last page for completed jobs
        this.loadLogs(true);
      }
    }

    // Handle isJobRunning changes
    if (changes['isJobRunning'] && !changes['isJobRunning'].firstChange) {
      if (this.isJobRunning && !this.streamingActive) {
        this.enableTailMode();
      } else if (!this.isJobRunning && this.streamingActive) {
        this.stopStreaming();
        this.tailMode = false;
        this.initialLoadComplete = false;
        // Reload to get complete logs from database, starting from last page
        this.loadLogs(true);
      }
    }
  }

  ngOnDestroy(): void {
    this.stopStreaming();
    this.cleanupBatching();

    // Clean up native event listener
    if (this.searchInputListener && this.searchInputRef?.nativeElement) {
      this.searchInputRef.nativeElement.removeEventListener('input', this.searchInputListener);
      this.searchInputListener = null;
    }

    // Complete the logBuffer$ subject to prevent memory leaks
    this.logBuffer$.complete();
    this.destroy$.next();
    this.destroy$.complete();
  }

  /**
   * TrackBy function for log entries to improve rendering performance.
   */
  trackByLogId(index: number, log: JobLogEntry): number {
    return log.id ?? log.sequenceNumber ?? index;
  }

  /**
   * Setup batching for WebSocket log entries to prevent UI lockup.
   * Logs are buffered and processed in batches every 100ms.
   */
  private setupLogBatching(): void {
    this.ngZone.runOutsideAngular(() => {
      const batchTrigger$ = timer(100, 100);
      this.batchSubscription = this.logBuffer$.pipe(
        buffer(batchTrigger$)
      ).subscribe(batch => {
        if (batch.length > 0) {
          this.ngZone.run(() => {
            this.processBatchedLogs(batch);
          });
        }
      });
    });
  }

  /**
   * Clean up batching subscriptions.
   */
  private cleanupBatching(): void {
    if (this.batchSubscription) {
      this.batchSubscription.unsubscribe();
      this.batchSubscription = null;
    }
  }

  /**
   * Process a batch of log entries at once (tail -f style with circular buffer).
   */
  private processBatchedLogs(batch: IngestLogEntry[]): void {
    let modified = false;

    for (const logEntry of batch) {
      // Skip duplicates
      if (logEntry.sequenceNumber <= this.lastSequenceNumber) {
        continue;
      }

      // Convert IngestLogEntry to JobLogEntry format
      const jobLogEntry: JobLogEntry = {
        id: logEntry.sequenceNumber,
        taskId: logEntry.taskId,
        timestamp: logEntry.timestamp,
        level: logEntry.level as LogLevel,
        source: logEntry.source as any,
        message: logEntry.message,
        sequenceNumber: logEntry.sequenceNumber
      };

      // Apply level filter
      if (!this.selectedLevels.has(jobLogEntry.level)) {
        continue;
      }

      // Apply search filter
      if (this.searchText && !jobLogEntry.message.toLowerCase().includes(this.searchText.toLowerCase())) {
        continue;
      }

      this.logs.push(jobLogEntry);
      this.lastSequenceNumber = logEntry.sequenceNumber;
      modified = true;

      // Update level counts
      this.levelCounts[jobLogEntry.level] = (this.levelCounts[jobLogEntry.level] || 0) + 1;
    }

    if (modified) {
      // Circular buffer: trim to maxTailLogs
      while (this.logs.length > this.maxTailLogs) {
        const removed = this.logs.shift();
        if (removed) {
          // Decrement level count
          this.levelCounts[removed.level] = Math.max(0, (this.levelCounts[removed.level] || 0) - 1);
        }
      }

      this.totalCount = this.logs.length;

      if (this.autoScroll) {
        setTimeout(() => this.scrollToBottom(), 50);
      }

      this.cdr.markForCheck();
    }
  }

  /**
   * Enable tail mode with WebSocket streaming.
   */
  enableTailMode(): void {
    this.tailMode = true;
    this.logs = [];
    this.levelCounts = {};
    this.totalCount = 0;
    this.lastSequenceNumber = 0;

    // First, load recent logs from server via tail endpoint
    this.loading = true;
    this.jobLogService.tailLogs(this.taskId, this.maxTailLogs).pipe(takeUntil(this.destroy$)).subscribe({
      next: (response) => {
        this.enabled = response.enabled;
        // Add existing logs
        if (response.logs && response.logs.length > 0) {
          for (const log of response.logs) {
            if (this.selectedLevels.has(log.level)) {
              this.logs.push(log);
              this.levelCounts[log.level] = (this.levelCounts[log.level] || 0) + 1;
              if (log.sequenceNumber > this.lastSequenceNumber) {
                this.lastSequenceNumber = log.sequenceNumber;
              }
            }
          }
        }
        this.totalCount = this.logs.length;
        this.loading = false;

        // Then start WebSocket streaming for new logs
        this.startStreaming();

        if (this.autoScroll) {
          setTimeout(() => this.scrollToBottom(), 100);
        }

        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = 'Failed to load initial logs: ' + (err.error?.error || err.message);
        this.loading = false;
        // Still try to start streaming
        this.startStreaming();
      }
    });
  }

  /**
   * Start WebSocket streaming for real-time logs.
   */
  private startStreaming(): void {
    if (this.streamingActive || !this.taskId) {
      return;
    }

    this.webSocketService.connect();
    this.streamingActive = true;

    // Subscribe to appropriate log topic based on log source
    if (this.logSource === 'vector-population') {
      this.wsSubscription = this.webSocketService.subscribeToVectorPopulationLogs(this.taskId)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (logEntry) => {
            this.logBuffer$.next(logEntry);
          },
          error: (err) => {
            console.error('Error receiving vector population logs:', err);
          }
        });
    } else {
      this.wsSubscription = this.webSocketService.subscribeToTaskLogs(this.taskId)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (logEntry) => {
            this.logBuffer$.next(logEntry);
          },
          error: (err) => {
            console.error('Error receiving ingest logs:', err);
          }
        });
    }
  }

  /**
   * Stop WebSocket streaming.
   */
  private stopStreaming(): void {
    if (this.wsSubscription) {
      this.wsSubscription.unsubscribe();
      this.wsSubscription = null;
    }

    if (this.taskId) {
      if (this.logSource === 'vector-population') {
        this.webSocketService.unsubscribeFromVectorPopulationLogs(this.taskId);
      } else {
        this.webSocketService.unsubscribeFromTaskLogs(this.taskId);
      }
    }

    this.streamingActive = false;
  }

  /**
   * Toggle tail mode on/off.
   */
  toggleTailMode(): void {
    if (this.tailMode) {
      // Disable tail mode
      this.stopStreaming();
      this.tailMode = false;
      this.loadLogs();
    } else {
      // Enable tail mode
      this.enableTailMode();
    }
  }

  loadLogs(startFromLastPage: boolean = false): void {
    if (!this.taskId) {
      this.logs = [];
      return;
    }

    // Don't load via HTTP if in tail mode
    if (this.tailMode) {
      return;
    }

    this.loading = true;
    this.error = null;

    const selectedLevelsArray = Array.from(this.selectedLevels);

    // If we need to start from last page and don't know total count yet, first get the count
    if (startFromLastPage && !this.initialLoadComplete) {
      this.jobLogService.getLogCount(this.taskId).pipe(takeUntil(this.destroy$)).subscribe({
        next: (countResponse) => {
          const totalPages = Math.ceil(countResponse.count / this.pageSize);
          this.currentPage = Math.max(0, totalPages - 1);
          this.pageInput = this.currentPage + 1;
          this.initialLoadComplete = true;
          this.fetchLogsPage(selectedLevelsArray);
        },
        error: () => {
          // If count fails, just load first page
          this.initialLoadComplete = true;
          this.fetchLogsPage(selectedLevelsArray);
        }
      });
    } else {
      this.fetchLogsPage(selectedLevelsArray);
    }
  }

  private fetchLogsPage(selectedLevelsArray: LogLevel[]): void {
    this.loadedFromArchive = false;

    this.jobLogService.getLogsForJob(this.taskId, {
      levels: selectedLevelsArray.length > 0 && selectedLevelsArray.length < this.allLevels.length
        ? selectedLevelsArray
        : undefined,
      search: this.searchText || undefined,
      page: this.currentPage,
      size: this.pageSize
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: (response: JobLogsResponse) => {
        this.enabled = response.enabled;
        this.logs = response.logs || [];
        this.totalCount = response.totalCount;
        this.levelCounts = response.levelCounts || {};
        this.pageInput = this.currentPage + 1;

        // If no logs from DB and job is not running, try archive
        if (this.logs.length === 0 && !this.isJobRunning) {
          this.tryLoadFromArchive(selectedLevelsArray);
          return;
        }

        this.loading = false;
        this.searchLoading = false;

        if (this.autoScroll && this.logContainer) {
          setTimeout(() => {
            this.scrollToBottom();
          }, 100);
        }

        this.cdr.markForCheck();
      },
      error: (err) => {
        // On error, also try archive
        if (!this.isJobRunning) {
          this.tryLoadFromArchive(selectedLevelsArray);
        } else {
          this.error = 'Failed to load logs: ' + (err.error?.error || err.message);
          this.loading = false;
          this.searchLoading = false;
          this.cdr.markForCheck();
        }
      }
    });
  }

  /**
   * Try to load logs from archive when live logs are not available.
   * Limits the number of logs to prevent memory issues and UI freezes.
   */
  private tryLoadFromArchive(selectedLevelsArray: LogLevel[]): void {
    this.jobLogService.getArchivedLogs(this.taskId).pipe(takeUntil(this.destroy$)).subscribe({
      next: (response) => {
        if (response.available && response.logs && response.logs.length > 0) {
          // Limit the number of archived logs to prevent memory issues
          let archivedLogs = response.logs;
          let wasTruncated = false;

          if (archivedLogs.length > this.maxArchiveLogs) {
            // Keep the last N logs (most recent)
            archivedLogs = archivedLogs.slice(-this.maxArchiveLogs);
            wasTruncated = true;
          }

          // Convert archived logs to JobLogEntry format
          this.logs = archivedLogs.map((archived: ArchivedLogEntry) => ({
            id: archived.sequenceNumber,
            taskId: archived.taskId,
            timestamp: archived.timestamp,
            level: archived.level as LogLevel,
            source: archived.source as any,
            message: archived.message,
            sequenceNumber: archived.sequenceNumber
          } as JobLogEntry));

          // Apply level filter on archived logs
          if (selectedLevelsArray.length > 0 && selectedLevelsArray.length < this.allLevels.length) {
            this.logs = this.logs.filter(log => selectedLevelsArray.includes(log.level));
          }

          // Apply search filter on archived logs
          if (this.searchText) {
            const searchLower = this.searchText.toLowerCase();
            this.logs = this.logs.filter(log => log.message.toLowerCase().includes(searchLower));
          }

          this.totalCount = wasTruncated
            ? response.logs.length  // Show actual total even if truncated
            : this.logs.length;
          this.loadedFromArchive = true;

          // Calculate level counts from the displayed logs
          this.levelCounts = {};
          for (const log of this.logs) {
            this.levelCounts[log.level] = (this.levelCounts[log.level] || 0) + 1;
          }

          this.loading = false;
          this.searchLoading = false;

          if (this.autoScroll && this.logContainer) {
            setTimeout(() => {
              this.scrollToBottom();
            }, 100);
          }

          this.cdr.markForCheck();
        } else {
          // No archived logs either
          this.logs = [];
          this.totalCount = 0;
          this.levelCounts = {};
          this.loading = false;
          this.searchLoading = false;
          this.cdr.markForCheck();
        }
      },
      error: (err) => {
        // Archive also failed, show original empty state
        this.logs = [];
        this.totalCount = 0;
        this.levelCounts = {};
        this.loading = false;
        this.searchLoading = false;
        this.cdr.markForCheck();
      }
    });
  }

  toggleLevel(level: LogLevel): void {
    if (this.selectedLevels.has(level)) {
      this.selectedLevels.delete(level);
    } else {
      this.selectedLevels.add(level);
    }
    this.currentPage = 0;

    // In tail mode, re-filter without reloading from server
    if (this.tailMode) {
      this.refilterTailLogs();
    } else {
      this.loadLogs();
    }
  }

  isLevelSelected(level: LogLevel): boolean {
    return this.selectedLevels.has(level);
  }

  selectAllLevels(): void {
    this.selectedLevels = new Set(this.allLevels);
    this.currentPage = 0;

    if (this.tailMode) {
      this.refilterTailLogs();
    } else {
      this.loadLogs();
    }
  }

  clearAllLevels(): void {
    this.selectedLevels.clear();
    this.currentPage = 0;

    if (this.tailMode) {
      this.refilterTailLogs();
    } else {
      this.loadLogs();
    }
  }

  /**
   * Re-filter logs in tail mode when filters change.
   * In tail mode we need to reload from the tail endpoint to get properly filtered results.
   */
  private refilterTailLogs(): void {
    // For real-time streaming, we reload the tail to get current filtered state
    // The circular buffer will be repopulated with matching logs
    this.enableTailMode();
  }

  onSearchChange(value: string): void {
    // Don't trigger change detection on every keystroke - just push to subject
    // The debounced subscription will handle updating the UI
    this.ngZone.runOutsideAngular(() => {
      this.searchSubject.next(value);
    });
  }

  clearSearch(): void {
    this.searchText = '';
    this.searchLoading = false;
    this.currentPage = 0;
    this.loadLogs();
  }

  downloadLogs(): void {
    this.jobLogService.downloadLogsAsFile(this.taskId);
  }

  refreshLogs(): void {
    if (this.tailMode) {
      this.enableTailMode();
    } else {
      this.loadLogs();
    }
  }

  /**
   * Fetch last N logs via HTTP (one-time tail, not streaming).
   * Use toggleTailMode() for continuous streaming.
   */
  tailLogs(): void {
    this.loading = true;
    this.jobLogService.tailLogs(this.taskId, this.maxTailLogs).pipe(takeUntil(this.destroy$)).subscribe({
      next: (response) => {
        this.logs = response.logs || [];
        this.totalCount = this.logs.length;
        this.loading = false;
        if (this.autoScroll) {
          setTimeout(() => this.scrollToBottom(), 100);
        }
      },
      error: (err) => {
        this.error = 'Failed to tail logs: ' + (err.error?.error || err.message);
        this.loading = false;
      }
    });
  }

  scrollToBottom(): void {
    if (this.logContainer?.nativeElement) {
      const el = this.logContainer.nativeElement;
      el.scrollTop = el.scrollHeight;
    }
  }

  scrollToTop(): void {
    if (this.logContainer?.nativeElement) {
      this.logContainer.nativeElement.scrollTop = 0;
    }
  }

  nextPage(): void {
    if ((this.currentPage + 1) * this.pageSize < this.totalCount) {
      this.currentPage++;
      this.loadLogs();
    }
  }

  prevPage(): void {
    if (this.currentPage > 0) {
      this.currentPage--;
      this.loadLogs();
    }
  }

  firstPage(): void {
    if (this.currentPage !== 0) {
      this.currentPage = 0;
      this.loadLogs();
    }
  }

  lastPage(): void {
    const lastPageIndex = Math.max(0, this.totalPages - 1);
    if (this.currentPage !== lastPageIndex) {
      this.currentPage = lastPageIndex;
      this.loadLogs();
    }
  }

  goToPage(): void {
    const targetPage = Math.max(1, Math.min(this.pageInput, this.totalPages));
    const targetIndex = targetPage - 1;
    if (targetIndex !== this.currentPage) {
      this.currentPage = targetIndex;
      this.loadLogs();
    }
    // Always update pageInput to show valid value
    this.pageInput = this.currentPage + 1;
  }

  onPageInputKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      this.goToPage();
    }
  }

  getLevelClass(level: LogLevel): string {
    return this.jobLogService.getLevelClass(level);
  }

  getLevelIcon(level: LogLevel): string {
    return this.jobLogService.getLevelIcon(level);
  }

  formatTimestamp(timestamp: string): string {
    return this.jobLogService.formatTimestamp(timestamp);
  }

  getLevelCount(level: LogLevel): number {
    return this.levelCounts[level] || 0;
  }

  get totalPages(): number {
    return Math.ceil(this.totalCount / this.pageSize);
  }

  get hasNextPage(): boolean {
    return (this.currentPage + 1) * this.pageSize < this.totalCount;
  }

  get hasPrevPage(): boolean {
    return this.currentPage > 0;
  }

  get displayedRange(): string {
    const start = this.currentPage * this.pageSize + 1;
    const end = Math.min((this.currentPage + 1) * this.pageSize, this.totalCount);
    return `${start}-${end} of ${this.totalCount}`;
  }
}
