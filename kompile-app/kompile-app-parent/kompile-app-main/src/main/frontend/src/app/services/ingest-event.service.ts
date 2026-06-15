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

import { Injectable, OnDestroy } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, BehaviorSubject, Subject, interval, Subscription, of } from 'rxjs';
import { catchError, tap, switchMap, filter, takeUntil, map } from 'rxjs/operators';
import { BaseService, backendUrl } from './base.service';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import {
  IngestEvent,
  IngestEventType,
  TaskEventsResponse,
  RecentEventsResponse,
  ErrorEventsResponse,
  TaskIdsResponse,
  EventLogStatusResponse,
  EventLogSummary,
  CleanupResponse,
  DeleteTaskEventsResponse,
  TaskEnvironmentResponse
} from '../models/api-models';

/**
 * Restart info for tracking active restarts.
 */
export interface RestartInfo {
  taskId: string;
  attemptNumber: number;
  maxAttempts: number;
  scheduledTime?: Date;
  heapSize?: string;
  heapIncreased?: boolean;
  ompThreads?: number;
  blasThreads?: number;
  batchSize?: number;
  memoryAnalysisReason?: string;
  systemRamTotal?: string;
  systemRamFree?: string;
}

/**
 * Restart status response from backend.
 */
export interface RestartStatus {
  taskId: string;
  restartEnabled: boolean;
  currentAttempt: number;
  maxAttempts: number;
  restartScheduled: boolean;
  nextRestartTime?: string;
  lastFailureReason?: string;
}

/**
 * Restart configuration response.
 */
export interface RestartConfig {
  enabled: boolean;
  maxAttempts: number;
  initialBackoffMs: number;
  backoffMultiplier: number;
  heapIncreaseFactor: number;
  systemRamSafetyMargin: number;
}

/**
 * Service for managing and retrieving ingest event logs.
 * Provides real-time updates via WebSocket and polling, plus API access to event data.
 */
@Injectable({
  providedIn: 'root'
})
export class IngestEventService extends BaseService implements OnDestroy {
  private readonly baseUrl: string;

  // WebSocket topics
  private static readonly WS_EVENTS_TOPIC = '/topic/ingest/events';
  private static readonly WS_EVENTS_TASK_TOPIC = '/topic/ingest/events/';

  // Real-time event updates
  private eventsSubject = new BehaviorSubject<IngestEvent[]>([]);
  private summarySubject = new BehaviorSubject<EventLogSummary | null>(null);
  private statusSubject = new BehaviorSubject<EventLogStatusResponse | null>(null);
  private newEventSubject = new Subject<IngestEvent>();
  private destroy$ = new Subject<void>();

  // WebSocket state
  private wsClient: Client | null = null;
  private wsSubscription: StompSubscription | null = null;
  private wsConnected = new BehaviorSubject<boolean>(false);

  // Polling state (fallback)
  private pollingSubscription: Subscription | null = null;
  private pollingInterval = 5000; // 5 seconds default
  private isPolling = false;

  // Restart tracking
  private activeRestartsSubject = new BehaviorSubject<Map<string, RestartInfo>>(new Map());

  // Observable streams
  public events$ = this.eventsSubject.asObservable();
  public summary$ = this.summarySubject.asObservable();
  public status$ = this.statusSubject.asObservable();
  public newEvent$ = this.newEventSubject.asObservable();
  public wsConnected$ = this.wsConnected.asObservable();
  public activeRestarts$ = this.activeRestartsSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
    this.baseUrl = `${this.backendUrl}/ingest/events`;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // STATUS & SUMMARY
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get event log status (enabled/disabled and total count).
   */
  getStatus(): Observable<EventLogStatusResponse> {
    return this.http.get<EventLogStatusResponse>(`${this.baseUrl}/status`).pipe(
      tap(status => this.statusSubject.next(status)),
      catchError(error => {
        console.error('Error fetching event log status:', error);
        throw error;
      })
    );
  }

  /**
   * Get summary statistics for a time range.
   */
  getSummary(hours: number = 24): Observable<EventLogSummary> {
    const params = new HttpParams().set('hours', hours.toString());
    return this.http.get<EventLogSummary>(`${this.baseUrl}/summary`, { params }).pipe(
      tap(summary => this.summarySubject.next(summary)),
      catchError(error => {
        console.error('Error fetching event log summary:', error);
        throw error;
      })
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // EVENT QUERIES
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get all events for a specific task.
   */
  getEventsForTask(taskId: string): Observable<TaskEventsResponse> {
    return this.http.get<TaskEventsResponse>(`${this.baseUrl}/task/${encodeURIComponent(taskId)}`).pipe(
      catchError(error => {
        console.error(`Error fetching events for task ${taskId}:`, error);
        throw error;
      })
    );
  }

  /**
   * Get the latest event for a task.
   */
  getLatestEvent(taskId: string): Observable<IngestEvent | null> {
    return this.http.get<IngestEvent>(`${this.baseUrl}/task/${encodeURIComponent(taskId)}/latest`).pipe(
      catchError(error => {
        if (error.status === 404) {
          return new Observable<IngestEvent | null>(subscriber => {
            subscriber.next(null);
            subscriber.complete();
          });
        }
        console.error(`Error fetching latest event for task ${taskId}:`, error);
        throw error;
      })
    );
  }

  /**
   * Get the ND4J environment snapshot captured when a job was queued.
   * This is useful for reproducing environment-specific issues.
   */
  getTaskEnvironment(taskId: string): Observable<TaskEnvironmentResponse> {
    return this.http.get<TaskEnvironmentResponse>(`${this.baseUrl}/task/${encodeURIComponent(taskId)}/environment`).pipe(
      catchError(error => {
        if (error.status === 404) {
          return new Observable<TaskEnvironmentResponse>(subscriber => {
            subscriber.next({
              taskId,
              fileName: '',
              timestamp: '',
              environmentCaptured: false,
              message: 'Task not found'
            });
            subscriber.complete();
          });
        }
        console.error(`Error fetching environment for task ${taskId}:`, error);
        throw error;
      })
    );
  }

  /**
   * Get recent terminal events (completions, failures, cancellations).
   */
  getRecentEvents(hours: number = 24): Observable<RecentEventsResponse> {
    const params = new HttpParams().set('hours', hours.toString());
    return this.http.get<RecentEventsResponse>(`${this.baseUrl}/recent`, { params }).pipe(
      tap(response => this.eventsSubject.next(response.events)),
      catchError(error => {
        console.error('Error fetching recent events:', error);
        throw error;
      })
    );
  }

  /**
   * Get events in a time range.
   */
  getEventsInRange(start: Date, end: Date): Observable<RecentEventsResponse> {
    const params = new HttpParams()
      .set('start', start.toISOString())
      .set('end', end.toISOString());
    return this.http.get<RecentEventsResponse>(`${this.baseUrl}/range`, { params }).pipe(
      catchError(error => {
        console.error('Error fetching events in range:', error);
        throw error;
      })
    );
  }

  /**
   * Get error events in a time range.
   */
  getErrorEvents(hours: number = 24): Observable<ErrorEventsResponse> {
    const params = new HttpParams().set('hours', hours.toString());
    return this.http.get<ErrorEventsResponse>(`${this.baseUrl}/errors`, { params }).pipe(
      catchError(error => {
        console.error('Error fetching error events:', error);
        throw error;
      })
    );
  }

  /**
   * Get distinct task IDs with events in a time range.
   */
  getTaskIds(hours: number = 24): Observable<TaskIdsResponse> {
    const params = new HttpParams().set('hours', hours.toString());
    return this.http.get<TaskIdsResponse>(`${this.baseUrl}/tasks`, { params }).pipe(
      catchError(error => {
        console.error('Error fetching task IDs:', error);
        throw error;
      })
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // EVENT MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Delete events for a specific task.
   */
  deleteTaskEvents(taskId: string): Observable<DeleteTaskEventsResponse> {
    return this.http.delete<DeleteTaskEventsResponse>(`${this.baseUrl}/task/${encodeURIComponent(taskId)}`).pipe(
      tap(() => {
        // Refresh the events list
        this.refreshEvents();
      }),
      catchError(error => {
        console.error(`Error deleting events for task ${taskId}:`, error);
        throw error;
      })
    );
  }

  /**
   * Force cleanup of old events.
   */
  forceCleanup(days: number = 7): Observable<CleanupResponse> {
    const params = new HttpParams().set('days', days.toString());
    return this.http.post<CleanupResponse>(`${this.baseUrl}/cleanup`, null, { params }).pipe(
      tap(response => {
        console.log(`Cleanup completed: ${response.deletedCount} events deleted`);
        // Refresh both events and summary
        this.refreshEvents();
        this.refreshSummary();
      }),
      catchError(error => {
        console.error('Error during cleanup:', error);
        throw error;
      })
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // WEBSOCKET REAL-TIME UPDATES
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Connect to WebSocket for real-time event updates.
   */
  connectWebSocket(): void {
    if (this.wsClient && this.wsConnected.getValue()) {
      console.log('WebSocket already connected for events');
      return;
    }

    const wsUrl = this.getWebSocketUrl();
    console.log('Connecting to WebSocket for events at:', wsUrl);

    this.wsClient = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      debug: (str) => {
        // Reduce debug noise
        if (str.includes('ERROR') || str.includes('CONNECT')) {
          console.log('STOMP Events:', str);
        }
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,

      onConnect: () => {
        console.log('WebSocket connected for event log');
        this.wsConnected.next(true);
        this.subscribeToEvents();
      },

      onDisconnect: () => {
        console.log('WebSocket disconnected for event log');
        this.wsConnected.next(false);
      },

      onStompError: (frame) => {
        console.error('STOMP error (events):', frame.headers['message']);
        this.wsConnected.next(false);
      },

      onWebSocketError: (event) => {
        console.error('WebSocket error (events):', event);
        this.wsConnected.next(false);
      }
    });

    this.wsClient.activate();
  }

  /**
   * Disconnect WebSocket.
   */
  disconnectWebSocket(): void {
    if (this.wsSubscription) {
      this.wsSubscription.unsubscribe();
      this.wsSubscription = null;
    }

    if (this.wsClient) {
      this.wsClient.deactivate();
      this.wsClient = null;
      this.wsConnected.next(false);
    }
    console.log('WebSocket disconnected for event log');
  }

  /**
   * Subscribe to global events topic.
   */
  private subscribeToEvents(): void {
    if (!this.wsClient || !this.wsConnected.getValue()) {
      console.warn('Cannot subscribe: WebSocket not connected');
      return;
    }

    const topic = IngestEventService.WS_EVENTS_TOPIC;
    console.log('Subscribing to events topic:', topic);

    this.wsSubscription = this.wsClient.subscribe(topic, (message: IMessage) => {
      try {
        const event: IngestEvent = JSON.parse(message.body);
        console.log('Received event via WebSocket:', event.eventType, event.taskId);
        this.handleIncomingEvent(event);
      } catch (error) {
        console.error('Failed to parse event message:', error);
      }
    });
  }

  // Maximum events to keep in memory to prevent RAM bloat
  private static readonly MAX_IN_MEMORY_EVENTS = 200;

  /**
   * Handle an incoming event from WebSocket.
   */
  private handleIncomingEvent(event: IngestEvent): void {
    // Emit new event
    this.newEventSubject.next(event);

    // Track restart events
    this.updateRestartTracking(event);

    // Add to events list
    const currentEvents = this.eventsSubject.getValue();
    // Check if event already exists
    if (!currentEvents.some(e => e.id === event.id)) {
      // Add new event at the beginning (newest first)
      const updatedEvents = [event, ...currentEvents];
      // Limit to avoid memory issues (keep last MAX_IN_MEMORY_EVENTS events)
      this.eventsSubject.next(updatedEvents.slice(0, IngestEventService.MAX_IN_MEMORY_EVENTS));
    }
  }

  /**
   * Update restart tracking based on incoming events.
   */
  private updateRestartTracking(event: IngestEvent): void {
    const restartEventTypes: IngestEventType[] = [
      'RESTART_SCHEDULED', 'RESTART_ATTEMPTED', 'RESTART_SUCCEEDED',
      'RESTART_FAILED', 'MEMORY_ANALYSIS', 'HEAP_ADJUSTED',
      'THREADS_REDUCED', 'MANUAL_RESTART'
    ];

    if (!restartEventTypes.includes(event.eventType)) {
      return;
    }

    const activeRestarts = new Map(this.activeRestartsSubject.getValue());

    switch (event.eventType) {
      case 'RESTART_SCHEDULED':
      case 'RESTART_ATTEMPTED':
      case 'MANUAL_RESTART':
        // Add or update restart info
        activeRestarts.set(event.taskId, {
          taskId: event.taskId,
          attemptNumber: event.restartAttempt || 1,
          maxAttempts: event.maxRestartAttempts || 3,
          scheduledTime: event.nextRestartTime ? new Date(event.nextRestartTime) : undefined,
          heapSize: event.heapSize,
          heapIncreased: event.heapIncreased,
          ompThreads: event.ompThreads,
          blasThreads: event.blasThreads,
          batchSize: event.batchSize,
          memoryAnalysisReason: event.memoryAnalysisReason,
          systemRamTotal: event.systemRamTotal ? this.formatBytes(event.systemRamTotal) : undefined,
          systemRamFree: event.systemRamFree ? this.formatBytes(event.systemRamFree) : undefined
        });
        break;

      case 'RESTART_SUCCEEDED':
      case 'RESTART_FAILED':
        // Remove from active restarts
        activeRestarts.delete(event.taskId);
        break;

      case 'MEMORY_ANALYSIS':
      case 'HEAP_ADJUSTED':
      case 'THREADS_REDUCED':
        // Update existing restart info with memory details
        const existing = activeRestarts.get(event.taskId);
        if (existing) {
          activeRestarts.set(event.taskId, {
            ...existing,
            heapSize: event.heapSize || existing.heapSize,
            heapIncreased: event.heapIncreased ?? existing.heapIncreased,
            ompThreads: event.ompThreads || existing.ompThreads,
            blasThreads: event.blasThreads || existing.blasThreads,
            memoryAnalysisReason: event.memoryAnalysisReason || existing.memoryAnalysisReason
          });
        }
        break;
    }

    this.activeRestartsSubject.next(activeRestarts);
  }

  /**
   * Format bytes to human-readable string.
   */
  private formatBytes(bytes: number): string {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  private getWebSocketUrl(): string {
    const baseUrl = backendUrl.replace('/api', '');
    return `${baseUrl}/ws`;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // REAL-TIME UPDATES (Polling - Fallback)
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Start polling for new events (fallback when WebSocket unavailable).
   */
  startPolling(intervalMs: number = 5000, lookbackHours: number = 24): void {
    if (this.isPolling) {
      console.log('Polling already active');
      return;
    }

    this.pollingInterval = intervalMs;
    this.isPolling = true;

    // Initial fetch
    this.refreshEvents(lookbackHours);
    this.refreshSummary(lookbackHours);

    // Try WebSocket first
    this.connectWebSocket();

    // Set up interval as fallback
    this.pollingSubscription = interval(intervalMs).pipe(
      switchMap(() => this.getRecentEvents(lookbackHours))
    ).subscribe({
      next: (response) => {
        // Only update if not getting real-time updates via WebSocket
        if (!this.wsConnected.getValue()) {
          // Check for new events
          const currentEvents = this.eventsSubject.getValue();
          const newEvents = response.events.filter(e =>
            !currentEvents.some(ce => ce.id === e.id)
          );

          // Emit new events
          newEvents.forEach(event => this.newEventSubject.next(event));

          // Update the events subject - limit to MAX_IN_MEMORY_EVENTS
          const limitedEvents = response.events.slice(0, IngestEventService.MAX_IN_MEMORY_EVENTS);
          this.eventsSubject.next(limitedEvents);
        }
      },
      error: (error) => {
        console.error('Polling error:', error);
      }
    });

    console.log(`Event polling started with ${intervalMs}ms interval`);
  }

  /**
   * Stop polling for events.
   */
  stopPolling(): void {
    if (this.pollingSubscription) {
      this.pollingSubscription.unsubscribe();
      this.pollingSubscription = null;
    }
    this.isPolling = false;

    // Also disconnect WebSocket
    this.disconnectWebSocket();
    console.log('Event polling stopped');
  }

  /**
   * Check if polling is active.
   */
  isPollingActive(): boolean {
    return this.isPolling;
  }

  /**
   * Check if WebSocket is connected.
   */
  isWebSocketConnected(): boolean {
    return this.wsConnected.getValue();
  }

  /**
   * Manually refresh events.
   */
  refreshEvents(hours: number = 24): void {
    this.getRecentEvents(hours).subscribe();
  }

  /**
   * Manually refresh summary.
   */
  refreshSummary(hours: number = 24): void {
    this.getSummary(hours).subscribe();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // RESTART CONTROL METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Trigger a manual restart for a task.
   */
  manualRestart(taskId: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/vector-population/subprocess/${encodeURIComponent(taskId)}/restart`, {}).pipe(
      tap(() => {
        console.log(`Manual restart initiated for task ${taskId}`);
      }),
      catchError(error => {
        console.error(`Failed to restart task ${taskId}:`, error);
        throw error;
      })
    );
  }

  /**
   * Get restart status for a task.
   */
  getRestartStatus(taskId: string): Observable<RestartStatus> {
    return this.http.get<RestartStatus>(`${this.backendUrl}/vector-population/subprocess/${encodeURIComponent(taskId)}/restart-status`).pipe(
      catchError(error => {
        console.error(`Failed to get restart status for task ${taskId}:`, error);
        throw error;
      })
    );
  }

  /**
   * Disable auto-restart for a task.
   */
  disableAutoRestart(taskId: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/vector-population/subprocess/${encodeURIComponent(taskId)}/disable-restart`, {}).pipe(
      tap(() => {
        console.log(`Auto-restart disabled for task ${taskId}`);
      }),
      catchError(error => {
        console.error(`Failed to disable auto-restart for task ${taskId}:`, error);
        throw error;
      })
    );
  }

  /**
   * Get global restart configuration.
   */
  getRestartConfig(): Observable<RestartConfig> {
    return this.http.get<RestartConfig>(`${this.backendUrl}/vector-population/restart-config`).pipe(
      catchError(error => {
        console.error('Failed to get restart configuration:', error);
        throw error;
      })
    );
  }

  /**
   * Enable or disable global restart functionality.
   */
  setRestartEnabled(enabled: boolean): Observable<any> {
    return this.http.post(`${this.backendUrl}/vector-population/restart-config/enabled?enabled=${enabled}`, {}).pipe(
      tap(() => {
        console.log(`Restart ${enabled ? 'enabled' : 'disabled'}`);
      }),
      catchError(error => {
        console.error('Failed to set restart enabled:', error);
        throw error;
      })
    );
  }

  /**
   * Set maximum restart attempts.
   */
  setMaxRestartAttempts(maxAttempts: number): Observable<any> {
    return this.http.post(`${this.backendUrl}/vector-population/restart-config/max-attempts?maxAttempts=${maxAttempts}`, {}).pipe(
      tap(() => {
        console.log(`Max restart attempts set to ${maxAttempts}`);
      }),
      catchError(error => {
        console.error('Failed to set max restart attempts:', error);
        throw error;
      })
    );
  }

  /**
   * Get active restarts map.
   */
  getActiveRestarts(): Map<string, RestartInfo> {
    return this.activeRestartsSubject.getValue();
  }

  /**
   * Check if a restart event type.
   */
  isRestartEvent(eventType: IngestEventType): boolean {
    return [
      'RESTART_SCHEDULED', 'RESTART_ATTEMPTED', 'RESTART_SUCCEEDED',
      'RESTART_FAILED', 'MEMORY_ANALYSIS', 'HEAP_ADJUSTED',
      'THREADS_REDUCED', 'MANUAL_RESTART'
    ].includes(eventType);
  }

  /**
   * Check if a memory-related event type.
   */
  isMemoryEvent(eventType: IngestEventType): boolean {
    return ['MEMORY_ANALYSIS', 'HEAP_ADJUSTED', 'THREADS_REDUCED'].includes(eventType);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // UTILITY METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get current cached events.
   */
  getCurrentEvents(): IngestEvent[] {
    return this.eventsSubject.getValue();
  }

  /**
   * Get current cached summary.
   */
  getCurrentSummary(): EventLogSummary | null {
    return this.summarySubject.getValue();
  }

  /**
   * Filter events by type.
   */
  filterEventsByType(events: IngestEvent[], types: string[]): IngestEvent[] {
    return events.filter(e => types.includes(e.eventType));
  }

  /**
   * Filter events by phase.
   */
  filterEventsByPhase(events: IngestEvent[], phases: string[]): IngestEvent[] {
    return events.filter(e => e.phase && phases.includes(e.phase));
  }

  /**
   * Group events by task ID.
   */
  groupEventsByTask(events: IngestEvent[]): Map<string, IngestEvent[]> {
    const grouped = new Map<string, IngestEvent[]>();
    events.forEach(event => {
      const taskEvents = grouped.get(event.taskId) || [];
      taskEvents.push(event);
      grouped.set(event.taskId, taskEvents);
    });
    return grouped;
  }

  /**
   * Sort events by timestamp (newest first).
   */
  sortEventsByTimestamp(events: IngestEvent[], ascending: boolean = false): IngestEvent[] {
    return [...events].sort((a, b) => {
      const timeA = new Date(a.timestamp).getTime();
      const timeB = new Date(b.timestamp).getTime();
      return ascending ? timeA - timeB : timeB - timeA;
    });
  }

  /**
   * Format duration in milliseconds to human-readable string.
   */
  formatDuration(ms: number | undefined): string {
    if (!ms) return '-';
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    if (ms < 3600000) return `${(ms / 60000).toFixed(1)}m`;
    return `${(ms / 3600000).toFixed(1)}h`;
  }

  /**
   * Format timestamp to local string.
   */
  formatTimestamp(timestamp: string): string {
    return new Date(timestamp).toLocaleString();
  }

  /**
   * Get relative time string (e.g., "5 minutes ago").
   */
  getRelativeTime(timestamp: string): string {
    const now = new Date().getTime();
    const time = new Date(timestamp).getTime();
    const diff = now - time;

    if (diff < 60000) return 'Just now';
    if (diff < 3600000) return `${Math.floor(diff / 60000)} min ago`;
    if (diff < 86400000) return `${Math.floor(diff / 3600000)} hours ago`;
    return `${Math.floor(diff / 86400000)} days ago`;
  }

  /**
   * Clean up on service destroy.
   */
  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.stopPolling();
    this.disconnectWebSocket();
  }
}
