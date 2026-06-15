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

import { Component, Input, OnInit, OnDestroy, OnChanges, SimpleChanges, ViewChild, ElementRef, AfterViewChecked, AfterViewInit, ChangeDetectorRef, inject, ChangeDetectionStrategy, NgZone } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpClientModule } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { Subscription, timer, Subject } from 'rxjs';
import { debounceTime, buffer } from 'rxjs/operators';
import { WebSocketService } from '../../services/websocket.service';
import { IndexBrowserService } from '../../services/index-browser.service';
import { IngestLogEntry } from '../../models/api-models';
import { backendUrl } from '../../services/base.service';

export type LogSourceType = 'ingest' | 'vector-population' | 'embedding' | 'vlm-test' | 'training' | 'serving';

@Component({
  selector: 'app-subprocess-logs',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    HttpClientModule,
    MatIconModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatTooltipModule,
    MatCheckboxModule
  ],
  templateUrl: './subprocess-logs.component.html',
  styleUrls: ['./subprocess-logs.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SubprocessLogsComponent implements OnInit, OnDestroy, OnChanges, AfterViewChecked, AfterViewInit {
  @Input() taskId: string = '';
  @Input() maxLogs: number = 500;
  @Input() height: string = '300px';
  @Input() logSource: LogSourceType = 'ingest';  // Default to ingest logs

  @ViewChild('logContainer') private logContainer!: ElementRef;
  @ViewChild('searchInput') private searchInputRef!: ElementRef<HTMLInputElement>;

  logs: IngestLogEntry[] = [];
  filteredLogs: IngestLogEntry[] = [];

  // Filter settings
  showDebug: boolean = false;
  showInfo: boolean = true;
  showWarn: boolean = true;
  showError: boolean = true;
  autoScroll: boolean = true;
  searchTerm: string = '';

  // Stats
  totalLogs: number = 0;
  errorCount: number = 0;
  warnCount: number = 0;

  private logSubscription: Subscription | null = null;
  private logPollingSubscription: Subscription | null = null;
  private shouldScroll: boolean = false;
  private lastSequenceNumber: number = 0; // Track last log sequence for incremental fetching
  private lastWebSocketLogTime: number = 0; // Track when we last received a WebSocket log

  // Batching subjects for performance - prevents UI lockup with rapid log streams
  private logBuffer$ = new Subject<IngestLogEntry>();
  private filterDebounce$ = new Subject<void>();
  private batchSubscription: Subscription | null = null;
  private filterSubscription: Subscription | null = null;

  // Update interval for batched change detection
  private updateInterval: any = null;
  private pendingUpdate: boolean = false;

  // Native event listener for search input (bypasses Angular change detection)
  private searchInputListener: ((e: Event) => void) | null = null;

  // Inject IndexBrowserService for fetching stored logs
  private indexBrowserService = inject(IndexBrowserService);

  constructor(
    private webSocketService: WebSocketService,
    private cdr: ChangeDetectorRef,
    private ngZone: NgZone,
    private http: HttpClient
  ) {}

  ngOnInit(): void {
    this.setupBatching();
    // Embedding logs don't need a taskId, subscribe immediately
    if (this.logSource === 'embedding') {
      // Load historical embedding logs first, then subscribe to WebSocket
      this.loadStoredEmbeddingLogs();
      this.subscribeToLogs();
    } else if (this.taskId) {
      this.subscribeToLogs();
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    const taskIdChanged = changes['taskId'] && !changes['taskId'].firstChange;
    const logSourceChanged = changes['logSource'] && !changes['logSource'].firstChange;

    if (taskIdChanged || logSourceChanged) {
      this.clearLogs();
      if (this.logSource === 'embedding') {
        this.loadStoredEmbeddingLogs();
        this.subscribeToLogs();
      } else if (this.taskId) {
        this.subscribeToLogs();
      } else {
        this.unsubscribe();
      }
    }
  }

  ngAfterViewInit(): void {
    // Attach native event listener to search input to bypass Angular change detection
    if (this.searchInputRef?.nativeElement) {
      this.searchInputListener = (e: Event) => {
        this.searchTerm = (e.target as HTMLInputElement).value;
        this.filterDebounce$.next();
      };
      this.ngZone.runOutsideAngular(() => {
        this.searchInputRef.nativeElement.addEventListener('input', this.searchInputListener!);
      });
    }
  }

  ngOnDestroy(): void {
    this.unsubscribe();
    this.cleanupBatching();

    // Clean up native event listener
    if (this.searchInputListener && this.searchInputRef?.nativeElement) {
      this.searchInputRef.nativeElement.removeEventListener('input', this.searchInputListener);
      this.searchInputListener = null;
    }
  }

  /**
   * Set up batching for log entries to prevent UI lockup.
   * Logs are buffered and processed in batches every 100ms.
   */
  private setupBatching(): void {
    // Run outside Angular zone to prevent change detection on every emit
    this.ngZone.runOutsideAngular(() => {
      // Buffer logs and process in batches every 100ms
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

      // Debounce filter application to prevent excessive recalculations
      this.filterSubscription = this.filterDebounce$.pipe(
        debounceTime(150)
      ).subscribe(() => {
        this.ngZone.run(() => {
          this.applyFilterInternal();
          this.cdr.markForCheck();
        });
      });
    });
  }

  /**
   * Clean up batching subscriptions and intervals.
   */
  private cleanupBatching(): void {
    if (this.batchSubscription) {
      this.batchSubscription.unsubscribe();
      this.batchSubscription = null;
    }
    if (this.filterSubscription) {
      this.filterSubscription.unsubscribe();
      this.filterSubscription = null;
    }
    if (this.updateInterval) {
      clearInterval(this.updateInterval);
      this.updateInterval = null;
    }
  }

  /**
   * Process a batch of log entries at once.
   */
  private processBatchedLogs(batch: IngestLogEntry[]): void {
    let modified = false;

    for (const logEntry of batch) {
      // Skip duplicates (already loaded from server)
      if (logEntry.sequenceNumber <= this.lastSequenceNumber) {
        continue;
      }

      this.logs.push(logEntry);
      this.totalLogs++;
      this.lastSequenceNumber = logEntry.sequenceNumber;
      modified = true;

      // Update stats
      if (logEntry.level === 'ERROR') this.errorCount++;
      if (logEntry.level === 'WARN') this.warnCount++;
    }

    if (modified) {
      // Trim if exceeds max
      while (this.logs.length > this.maxLogs) {
        const removed = this.logs.shift();
        if (removed?.level === 'ERROR') this.errorCount--;
        if (removed?.level === 'WARN') this.warnCount--;
      }

      this.applyFilterInternal();
      this.shouldScroll = true;
      this.cdr.markForCheck();
    }
  }

  ngAfterViewChecked(): void {
    if (this.shouldScroll && this.autoScroll) {
      this.scrollToBottom();
      this.shouldScroll = false;
    }
  }

  /**
   * Subscribe to logs for the given task ID via WebSocket.
   * Logs are streamed in real-time only - no server-side storage/restoration.
   */
  subscribeToLogs(): void {
    this.unsubscribe();

    // Connect WebSocket first
    this.webSocketService.connect();

    // Choose the appropriate WebSocket subscription based on log source
    if (this.logSource === 'embedding') {
      // Embedding subprocess logs - no task ID needed, single global stream
      this.logSubscription = this.webSocketService.subscribeToEmbeddingSubprocess()
        .subscribe({
          next: (event) => {
            this.lastWebSocketLogTime = Date.now();
            // Convert embedding subprocess event to log entry format
            const logEntry = this.convertEmbeddingEventToLogEntry(event);
            if (logEntry) {
              this.logBuffer$.next(logEntry);
            }
          },
          error: (err) => {
            console.error('Error receiving embedding subprocess logs:', err);
          }
        });
    } else if (this.logSource === 'vector-population') {
      if (!this.taskId) return;
      this.logSubscription = this.webSocketService.subscribeToVectorPopulationLogs(this.taskId)
        .subscribe({
          next: (logEntry) => {
            this.lastWebSocketLogTime = Date.now();
            // Buffer the log entry for batch processing
            this.logBuffer$.next(logEntry);
          },
          error: (err) => {
            console.error('Error receiving vector population logs:', err);
          }
        });
    } else if (this.logSource === 'vlm-test') {
      if (!this.taskId) return;
      this.logSubscription = this.webSocketService.subscribeToVlmTestLogs(this.taskId)
        .subscribe({
          next: (logEntry) => {
            this.lastWebSocketLogTime = Date.now();
            this.logBuffer$.next(logEntry);
          },
          error: (err) => {
            console.error('Error receiving VLM test logs:', err);
          }
        });
    } else {
      // Ingest, training, serving, and other subprocess types all use
      // the same INGEST_MSG: wire protocol and /topic/ingest/{taskId}/logs topic
      if (!this.taskId) return;
      this.logSubscription = this.webSocketService.subscribeToTaskLogs(this.taskId)
        .subscribe({
          next: (logEntry) => {
            this.lastWebSocketLogTime = Date.now();
            this.logBuffer$.next(logEntry);
          },
          error: (err) => {
            console.error(`Error receiving ${this.logSource} logs:`, err);
          }
        });
    }
  }

  /**
   * Convert embedding subprocess event to IngestLogEntry format.
   */
  private convertEmbeddingEventToLogEntry(event: any): IngestLogEntry | null {
    if (!event) return null;

    // Generate a sequence number based on timestamp
    const seqNum = event.timestamp || Date.now();

    // Map event type to log level
    type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
    let level: LogLevel = 'INFO';
    let message = '';

    switch (event.eventType) {
      case 'LOG':
        // Map the level from event, defaulting to INFO
        const eventLevel = (event.data?.level || 'INFO').toUpperCase();
        level = (['DEBUG', 'INFO', 'WARN', 'ERROR'].includes(eventLevel) ? eventLevel : 'INFO') as LogLevel;
        message = `[${event.data?.source || 'subprocess'}] ${event.data?.message || ''}`;
        break;
      case 'PROGRESS':
        level = 'INFO';
        message = `[${event.data?.phase || 'progress'}] ${event.data?.progressPercent || 0}% - ${event.data?.message || event.data?.currentStep || ''}`;
        break;
      case 'PHASE_TRANSITION':
        level = 'INFO';
        message = `Phase: ${event.data?.fromPhase || 'start'} -> ${event.data?.toPhase || 'unknown'} (${event.data?.durationMs || 0}ms)`;
        break;
      case 'ERROR':
        level = 'ERROR';
        message = `[${event.data?.errorType || 'error'}] ${event.data?.errorMessage || 'Unknown error'}`;
        break;
      case 'MODEL_LOADED':
        level = 'INFO';
        message = `Model loaded: ${event.modelId} (dimensions=${event.data?.dimensions}, type=${event.data?.encoderType})`;
        break;
      case 'MODEL_FAILED':
        level = 'ERROR';
        message = `Model failed to load: ${event.modelId} - ${event.data?.error || 'Unknown error'}`;
        break;
      case 'SUBPROCESS_STARTED':
        level = 'INFO';
        message = `Embedding subprocess started for model: ${event.modelId}`;
        break;
      case 'SUBPROCESS_STOPPED':
        level = 'INFO';
        message = `Embedding subprocess stopped for model: ${event.modelId}`;
        break;
      case 'SUBPROCESS_CRASHED':
        level = 'ERROR';
        message = `Embedding subprocess crashed: ${event.data?.error || 'Unknown error'}`;
        break;
      case 'HEARTBEAT':
        // Skip heartbeat events
        return null;
      default:
        level = 'DEBUG';
        message = `Event: ${event.eventType} - ${JSON.stringify(event.data || {})}`;
    }

    return {
      taskId: event.modelId || 'embedding',
      level: level,
      source: 'EMBEDDING',
      message: message,
      timestamp: new Date(event.timestamp || Date.now()).toISOString(),
      sequenceNumber: seqNum
    };
  }

  /**
   * @deprecated Log polling is no longer supported - logs are WebSocket-only
   */
  private startLogPolling(): void {
    // No-op: log polling removed - logs are streamed via WebSocket only
    // Historical job data is available in Job History
  }

  /**
   * @deprecated Log polling is no longer supported
   */
  private stopLogPolling(): void {
    if (this.logPollingSubscription) {
      this.logPollingSubscription.unsubscribe();
      this.logPollingSubscription = null;
    }
  }

  /**
   * Set a new task ID and resubscribe
   */
  setTaskId(taskId: string): void {
    this.taskId = taskId;
    this.clearLogs();
    this.subscribeToLogs();
  }

  /**
   * Set task ID and load existing logs from server (for page reload recovery)
   */
  setTaskIdAndLoadLogs(taskId: string): void {
    this.taskId = taskId;
    this.clearLogs();

    // Load existing logs from server first
    if (this.logSource === 'vector-population') {
      this.loadStoredVectorPopulationLogs(taskId);
    }

    // Then subscribe to WebSocket for new logs
    this.subscribeToLogs();
  }

  /**
   * Load existing logs from server (for page reload recovery)
   */
  loadStoredVectorPopulationLogs(taskId: string): void {
    this.indexBrowserService.getVectorPopulationTaskLogs(taskId).subscribe({
      next: (response) => {
        if (response.available && response.logs && response.logs.length > 0) {

          // Add all stored logs
          response.logs.forEach((log: any) => {
            const logEntry: IngestLogEntry = {
              taskId: log.taskId,
              level: log.level,
              source: log.source,
              message: log.message,
              timestamp: log.timestamp,
              sequenceNumber: log.sequenceNumber
            };

            // Add log without triggering subscription processing
            this.logs.push(logEntry);
            this.totalLogs++;

            // Update stats
            if (logEntry.level === 'ERROR') this.errorCount++;
            if (logEntry.level === 'WARN') this.warnCount++;

            // Track the last sequence number
            if (logEntry.sequenceNumber > this.lastSequenceNumber) {
              this.lastSequenceNumber = logEntry.sequenceNumber;
            }
          });

          // Apply filter and scroll
          this.applyFilterInternal();
          this.shouldScroll = true;
          this.cdr.markForCheck();
        }
      },
      error: () => {
        // Still subscribe to WebSocket even if loading fails
      }
    });
  }

  /**
   * Load stored embedding subprocess logs from the backend.
   * These are cached recent logs that were broadcast before the component was initialized.
   */
  loadStoredEmbeddingLogs(): void {
    const limit = this.maxLogs;
    this.http.get<any>(`${backendUrl}/models/subprocess/logs`, { params: { limit: limit.toString() } }).subscribe({
      next: (response) => {
        if (response.status === 'success' && response.logs && response.logs.length > 0) {
          console.log(`[EMBEDDING-LOGS] Loaded ${response.logs.length} historical embedding logs`);

          // Process each stored log entry
          response.logs.forEach((event: any) => {
            const logEntry = this.convertEmbeddingEventToLogEntry(event);
            if (logEntry) {
              // Add log without triggering subscription processing
              this.logs.push(logEntry);
              this.totalLogs++;

              // Update stats
              if (logEntry.level === 'ERROR') this.errorCount++;
              if (logEntry.level === 'WARN') this.warnCount++;

              // Track the last sequence number
              if (logEntry.sequenceNumber > this.lastSequenceNumber) {
                this.lastSequenceNumber = logEntry.sequenceNumber;
              }
            }
          });

          // Apply filter and scroll
          this.applyFilterInternal();
          this.shouldScroll = true;
          this.cdr.markForCheck();
        }
      },
      error: (err) => {
        console.error('[EMBEDDING-LOGS] Failed to load stored embedding logs:', err);
        // Still subscribe to WebSocket even if loading fails
      }
    });
  }

  /**
   * Load logs from a pre-fetched array (for bulk state restore)
   */
  loadLogsFromArray(logs: any[]): void {
    if (!logs || logs.length === 0) return;

    logs.forEach((log: any) => {
      const logEntry: IngestLogEntry = {
        taskId: log.taskId,
        level: log.level,
        source: log.source,
        message: log.message,
        timestamp: log.timestamp,
        sequenceNumber: log.sequenceNumber
      };

      this.logs.push(logEntry);
      this.totalLogs++;

      if (logEntry.level === 'ERROR') this.errorCount++;
      if (logEntry.level === 'WARN') this.warnCount++;

      if (logEntry.sequenceNumber > this.lastSequenceNumber) {
        this.lastSequenceNumber = logEntry.sequenceNumber;
      }
    });

    this.applyFilterInternal();
    this.shouldScroll = true;
    this.cdr.markForCheck();
  }

  /**
   * @deprecated Use logBuffer$ for batched processing instead.
   * Kept for backward compatibility with loadLogsFromArray.
   */
  private addLogDirect(logEntry: IngestLogEntry): void {
    // Skip duplicates (already loaded from server)
    if (logEntry.sequenceNumber <= this.lastSequenceNumber) {
      return;
    }

    this.logs.push(logEntry);
    this.totalLogs++;
    this.lastSequenceNumber = logEntry.sequenceNumber;

    // Update stats
    if (logEntry.level === 'ERROR') this.errorCount++;
    if (logEntry.level === 'WARN') this.warnCount++;
  }

  /**
   * Apply filters to logs (debounced for user input).
   * Called from template on filter changes.
   */
  applyFilter(): void {
    // Trigger debounced filter application
    this.filterDebounce$.next();
  }

  /**
   * Internal filter application - called after debounce or directly for batch processing.
   */
  private applyFilterInternal(): void {
    const searchTermLower = this.searchTerm?.toLowerCase() || '';

    this.filteredLogs = this.logs.filter(log => {
      // Level filter
      if (log.level === 'DEBUG' && !this.showDebug) return false;
      if (log.level === 'INFO' && !this.showInfo) return false;
      if (log.level === 'WARN' && !this.showWarn) return false;
      if (log.level === 'ERROR' && !this.showError) return false;

      // Search filter
      if (searchTermLower && !log.message.toLowerCase().includes(searchTermLower)) {
        return false;
      }

      return true;
    });
  }

  /**
   * Get CSS class for log level
   */
  getLogClass(level: string): string {
    switch (level) {
      case 'ERROR': return 'log-error';
      case 'WARN': return 'log-warn';
      case 'DEBUG': return 'log-debug';
      default: return 'log-info';
    }
  }

  /**
   * Get icon for log level
   */
  getLogIcon(level: string): string {
    switch (level) {
      case 'ERROR': return 'error';
      case 'WARN': return 'warning';
      case 'DEBUG': return 'bug_report';
      default: return 'info';
    }
  }

  /**
   * Format timestamp
   */
  formatTime(timestamp: string): string {
    try {
      const date = new Date(timestamp);
      return date.toLocaleTimeString('en-US', {
        hour12: false,
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        fractionalSecondDigits: 3
      });
    } catch {
      return timestamp;
    }
  }

  /**
   * Clear all logs
   */
  clearLogs(): void {
    this.logs = [];
    this.filteredLogs = [];
    this.totalLogs = 0;
    this.errorCount = 0;
    this.warnCount = 0;
    this.lastSequenceNumber = 0;
    this.lastWebSocketLogTime = 0;
  }

  /**
   * Toggle auto-scroll
   */
  toggleAutoScroll(): void {
    this.autoScroll = !this.autoScroll;
    if (this.autoScroll) {
      this.scrollToBottom();
    }
  }

  /**
   * Scroll to bottom of log container
   */
  scrollToBottom(): void {
    try {
      if (this.logContainer) {
        this.logContainer.nativeElement.scrollTop = this.logContainer.nativeElement.scrollHeight;
      }
    } catch (err) {
      console.error('Error scrolling:', err);
    }
  }

  /**
   * Copy logs to clipboard
   */
  copyLogs(): void {
    const text = this.filteredLogs
      .map(log => `[${this.formatTime(log.timestamp)}] [${log.level}] ${log.message}`)
      .join('\n');
    navigator.clipboard.writeText(text);
  }

  /**
   * Download logs as file
   */
  downloadLogs(): void {
    const text = this.filteredLogs
      .map(log => `[${this.formatTime(log.timestamp)}] [${log.level}] [${log.source}] ${log.message}`)
      .join('\n');
    const blob = new Blob([text], { type: 'text/plain' });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `subprocess-logs-${this.taskId}-${new Date().toISOString().slice(0, 19).replace(/:/g, '-')}.txt`;
    a.click();
    window.URL.revokeObjectURL(url);
  }

  /**
   * TrackBy function for ngFor
   */
  trackBySeq(index: number, log: IngestLogEntry): number {
    return log.sequenceNumber;
  }

  private unsubscribe(): void {
    // Stop log polling
    this.stopLogPolling();

    if (this.logSubscription) {
      this.logSubscription.unsubscribe();
      this.logSubscription = null;
    }

    // Unsubscribe from the appropriate log source
    if (this.logSource === 'embedding') {
      this.webSocketService.unsubscribeFromEmbeddingSubprocess();
    } else if (this.taskId) {
      if (this.logSource === 'vector-population') {
        this.webSocketService.unsubscribeFromVectorPopulationLogs(this.taskId);
      } else if (this.logSource === 'vlm-test') {
        this.webSocketService.unsubscribeFromVlmTestLogs(this.taskId);
      } else {
        // Ingest, training, serving all use the same ingest log topic
        this.webSocketService.unsubscribeFromTaskLogs(this.taskId);
      }
    }
  }
}
