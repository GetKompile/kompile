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

import { Injectable, NgZone, OnDestroy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import { BehaviorSubject, Observable, Subject } from 'rxjs';
import { filter, map } from 'rxjs/operators';
import { IngestProgressUpdate, IngestLogEntry, SystemResourcesResponse, ModelStatusUpdate, EnrichmentProgressUpdate } from '../models/api-models';
import { MonitorEvent } from '../models/monitor-models';
import { BaseService, backendUrl } from './base.service';
import SockJS from 'sockjs-client';

export enum WebSocketConnectionState {
  DISCONNECTED = 'DISCONNECTED',
  CONNECTING = 'CONNECTING',
  CONNECTED = 'CONNECTED',
  ERROR = 'ERROR'
}

@Injectable({
  providedIn: 'root'
})
export class WebSocketService extends BaseService implements OnDestroy {
  private client: Client | null = null;
  private subscriptions: Map<string, StompSubscription> = new Map();

  private connectionState = new BehaviorSubject<WebSocketConnectionState>(WebSocketConnectionState.DISCONNECTED);
  public connectionState$ = this.connectionState.asObservable();

  private progressUpdates = new Subject<IngestProgressUpdate>();
  public progressUpdates$ = this.progressUpdates.asObservable();

  // System resources WebSocket updates
  private systemResourceUpdates = new Subject<SystemResourcesResponse>();
  public systemResourceUpdates$ = this.systemResourceUpdates.asObservable();

  // Vector population WebSocket updates
  private vectorPopulationUpdates = new Subject<any>();
  public vectorPopulationUpdates$ = this.vectorPopulationUpdates.asObservable();

  // Subprocess log updates
  private logUpdates = new Subject<IngestLogEntry>();
  public logUpdates$ = this.logUpdates.asObservable();

  // Model/Embedding status updates
  private modelStatusUpdates = new Subject<ModelStatusUpdate>();
  public modelStatusUpdates$ = this.modelStatusUpdates.asObservable();

  constructor(private ngZone: NgZone, private http: HttpClient) {
    super();
  }

  /**
   * Connect to the WebSocket server
   */
  connect(): void {
    // Prevent race condition: don't create a new client if already connected or connecting
    if (this.client && (this.connectionState.value === WebSocketConnectionState.CONNECTED ||
                        this.connectionState.value === WebSocketConnectionState.CONNECTING)) {
      console.debug('WebSocket already connected or connecting, state:', this.connectionState.value);
      return;
    }

    this.connectionState.next(WebSocketConnectionState.CONNECTING);

    // Construct WebSocket URL from the backend URL
    const wsUrl = this.getWebSocketUrl();
    console.debug('Connecting to WebSocket at:', wsUrl);

    this.client = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      debug: (str) => {
        console.debug('STOMP:', str);
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,

      onConnect: () => {
        console.debug('WebSocket connected');
        this.connectionState.next(WebSocketConnectionState.CONNECTED);
      },

      onDisconnect: () => {
        console.debug('WebSocket disconnected');
        this.connectionState.next(WebSocketConnectionState.DISCONNECTED);
      },

      onStompError: (frame) => {
        console.error('STOMP error:', frame.headers['message'], frame.body);
        this.connectionState.next(WebSocketConnectionState.ERROR);
      },

      onWebSocketError: (event) => {
        console.error('WebSocket error:', event);
        this.connectionState.next(WebSocketConnectionState.ERROR);
      }
    });

    this.client.activate();
  }

  /**
   * Disconnect from the WebSocket server
   */
  disconnect(): void {
    if (this.client) {
      // Unsubscribe from all topics
      this.subscriptions.forEach((sub) => {
        sub.unsubscribe();
      });
      this.subscriptions.clear();

      this.client.deactivate();
      this.client = null;
      this.connectionState.next(WebSocketConnectionState.DISCONNECTED);
    }
  }

  /**
   * Subscribe to progress updates for a specific task
   */
  subscribeToTask(taskId: string): Observable<IngestProgressUpdate> {
    const topic = `/topic/ingest/${taskId}`;
    this.subscribeToTopic(topic);

    return this.progressUpdates$.pipe(
      filter(update => update.taskId === taskId)
    );
  }

  /**
   * Subscribe to all ingest progress updates
   */
  subscribeToAllTasks(): Observable<IngestProgressUpdate> {
    const topic = '/topic/ingest/all';
    this.subscribeToTopic(topic);
    return this.progressUpdates$;
  }

  /**
   * Unsubscribe from a specific task's updates
   */
  unsubscribeFromTask(taskId: string): void {
    const topic = `/topic/ingest/${taskId}`;
    this.unsubscribeFromTopic(topic);
  }

  // ==================== Subprocess Log WebSocket ====================

  /**
   * Subscribe to log updates for a specific task
   */
  subscribeToTaskLogs(taskId: string): Observable<IngestLogEntry> {
    const topic = `/topic/ingest/${taskId}/logs`;
    this.subscribeToLogTopic(topic);

    return this.logUpdates$.pipe(
      filter(log => log.taskId === taskId)
    );
  }

  /**
   * Subscribe to all subprocess log updates
   */
  subscribeToAllLogs(): Observable<IngestLogEntry> {
    const topic = '/topic/ingest/logs';
    this.subscribeToLogTopic(topic);
    return this.logUpdates$;
  }

  /**
   * Unsubscribe from a specific task's log updates
   */
  unsubscribeFromTaskLogs(taskId: string): void {
    const topic = `/topic/ingest/${taskId}/logs`;
    this.unsubscribeFromTopic(topic);
  }

  private subscribeToLogTopic(topic: string): void {
    if (!this.client || this.connectionState.value !== WebSocketConnectionState.CONNECTED) {
      const sub = this.connectionState$.pipe(
        filter(state => state === WebSocketConnectionState.CONNECTED)
      ).subscribe(() => {
        this.doSubscribeLogs(topic);
        sub.unsubscribe();
      });
      return;
    }

    this.doSubscribeLogs(topic);
  }

  private doSubscribeLogs(topic: string): void {
    if (this.subscriptions.has(topic)) {
      return;
    }

    if (!this.client) {
      console.error('Cannot subscribe to logs: client is null');
      return;
    }

    const subscription = this.client.subscribe(topic, (message: IMessage) => {
      this.ngZone.run(() => {
        try {
          const logEntry: IngestLogEntry = JSON.parse(message.body);
          this.logUpdates.next(logEntry);
        } catch (error) {
          console.error('Failed to parse log entry:', error);
        }
      });
    });

    this.subscriptions.set(topic, subscription);
  }

  // ==================== Vector Population WebSocket ====================

  private static readonly VECTOR_POPULATION_TOPIC = '/topic/vector-population/progress';
  private static readonly VECTOR_POPULATION_LOGS_TOPIC = '/topic/vector-population/logs';

  // Vector population log updates
  private vectorPopulationLogUpdates = new Subject<IngestLogEntry>();
  public vectorPopulationLogUpdates$ = this.vectorPopulationLogUpdates.asObservable();

  /**
   * Subscribe to vector population progress updates via WebSocket.
   */
  subscribeToVectorPopulation<T = any>(): Observable<T> {
    const topic = WebSocketService.VECTOR_POPULATION_TOPIC;
    this.subscribeToVectorPopulationTopic(topic);
    return this.vectorPopulationUpdates$ as Observable<T>;
  }

  /**
   * Unsubscribe from vector population updates.
   */
  unsubscribeFromVectorPopulation(): void {
    const topic = WebSocketService.VECTOR_POPULATION_TOPIC;
    this.unsubscribeFromTopic(topic);
  }

  /**
   * Subscribe to vector population log updates for a specific task.
   */
  subscribeToVectorPopulationLogs(taskId: string): Observable<IngestLogEntry> {
    const topic = `${WebSocketService.VECTOR_POPULATION_LOGS_TOPIC}/${taskId}`;
    this.subscribeToVectorPopulationLogTopic(topic);

    return this.vectorPopulationLogUpdates$.pipe(
      filter(log => log.taskId === taskId)
    );
  }

  /**
   * Subscribe to all vector population log updates.
   */
  subscribeToAllVectorPopulationLogs(): Observable<IngestLogEntry> {
    const topic = WebSocketService.VECTOR_POPULATION_LOGS_TOPIC;
    this.subscribeToVectorPopulationLogTopic(topic);
    return this.vectorPopulationLogUpdates$;
  }

  /**
   * Unsubscribe from vector population logs for a specific task.
   */
  unsubscribeFromVectorPopulationLogs(taskId: string): void {
    const topic = `${WebSocketService.VECTOR_POPULATION_LOGS_TOPIC}/${taskId}`;
    this.unsubscribeFromTopic(topic);
  }

  private subscribeToVectorPopulationLogTopic(topic: string): void {
    if (!this.client || this.connectionState.value !== WebSocketConnectionState.CONNECTED) {
      const sub = this.connectionState$.pipe(
        filter(state => state === WebSocketConnectionState.CONNECTED)
      ).subscribe(() => {
        this.doSubscribeVectorPopulationLogs(topic);
        sub.unsubscribe();
      });
      return;
    }

    this.doSubscribeVectorPopulationLogs(topic);
  }

  private doSubscribeVectorPopulationLogs(topic: string): void {
    if (this.subscriptions.has(topic)) {
      return;
    }

    if (!this.client) {
      console.error('Cannot subscribe to vector population logs: client is null');
      return;
    }

    const subscription = this.client.subscribe(topic, (message: IMessage) => {
      this.ngZone.run(() => {
        try {
          const logEntry: IngestLogEntry = JSON.parse(message.body);
          this.vectorPopulationLogUpdates.next(logEntry);
        } catch (error) {
          console.error('Failed to parse vector population log entry:', error);
        }
      });
    });

    this.subscriptions.set(topic, subscription);
  }

  private subscribeToVectorPopulationTopic(topic: string): void {
    if (!this.client || this.connectionState.value !== WebSocketConnectionState.CONNECTED) {
      const sub = this.connectionState$.pipe(
        filter(state => state === WebSocketConnectionState.CONNECTED)
      ).subscribe(() => {
        this.doSubscribeVectorPopulation(topic);
        sub.unsubscribe();
      });
      return;
    }

    this.doSubscribeVectorPopulation(topic);
  }

  private doSubscribeVectorPopulation(topic: string): void {
    if (this.subscriptions.has(topic)) {
      return;
    }

    if (!this.client) {
      console.error('Cannot subscribe: client is null');
      return;
    }

    const subscription = this.client.subscribe(topic, (message: IMessage) => {
      this.ngZone.run(() => {
        try {
          const update = JSON.parse(message.body);
          this.vectorPopulationUpdates.next(update);
        } catch (error) {
          console.error('Failed to parse vector population update:', error);
        }
      });
    });

    this.subscriptions.set(topic, subscription);
  }

  // ==================== System Resources WebSocket ====================

  private static readonly SYSTEM_RESOURCES_TOPIC = '/topic/system/resources';

  /**
   * Subscribe to real-time system resource updates via WebSocket.
   * Also notifies the backend to start broadcasting.
   */
  subscribeToSystemResources(): Observable<SystemResourcesResponse> {
    const topic = WebSocketService.SYSTEM_RESOURCES_TOPIC;

    // Notify backend to start broadcasting
    this.http.post(`${backendUrl}/system/broadcast/subscribe`, {}).subscribe({
      error: (err) => {
        console.error('Failed to subscribe to system resource broadcast:', err);
      }
    });

    this.subscribeToSystemResourceTopic(topic);
    return this.systemResourceUpdates$;
  }

  /**
   * Unsubscribe from system resource updates.
   * Also notifies the backend to potentially stop broadcasting.
   */
  unsubscribeFromSystemResources(): void {
    const topic = WebSocketService.SYSTEM_RESOURCES_TOPIC;

    // Notify backend to potentially stop broadcasting
    this.http.post(`${backendUrl}/system/broadcast/unsubscribe`, {}).subscribe({
      error: (err) => {
        console.error('Failed to unsubscribe from system resource broadcast:', err);
      }
    });

    this.unsubscribeFromTopic(topic);
  }

  private subscribeToSystemResourceTopic(topic: string): void {
    if (!this.client || this.connectionState.value !== WebSocketConnectionState.CONNECTED) {
      const sub = this.connectionState$.pipe(
        filter(state => state === WebSocketConnectionState.CONNECTED)
      ).subscribe(() => {
        this.doSubscribeSystemResources(topic);
        sub.unsubscribe();
      });
      return;
    }

    this.doSubscribeSystemResources(topic);
  }

  private doSubscribeSystemResources(topic: string): void {
    if (this.subscriptions.has(topic)) {
      return;
    }

    if (!this.client) {
      console.error('Cannot subscribe: client is null');
      return;
    }

    const subscription = this.client.subscribe(topic, (message: IMessage) => {
      this.ngZone.run(() => {
        try {
          const update: SystemResourcesResponse = JSON.parse(message.body);
          this.systemResourceUpdates.next(update);
        } catch (error) {
          console.error('Failed to parse system resource update:', error);
        }
      });
    });

    this.subscriptions.set(topic, subscription);
  }

  // ==================== Model/Embedding Status WebSocket ====================

  private static readonly MODEL_STATUS_TOPIC = '/topic/model/status';

  /**
   * Subscribe to real-time model/embedding status updates via WebSocket.
   * Provides live updates about embedding model state and staging connection.
   * Also notifies the backend to start broadcasting.
   */
  subscribeToModelStatus(): Observable<ModelStatusUpdate> {
    const topic = WebSocketService.MODEL_STATUS_TOPIC;

    // Notify backend to start broadcasting
    this.http.post(`${backendUrl}/staging-config/broadcast/subscribe`, {}).subscribe({
      next: (response: any) => {
        console.debug('[WS-MODEL] Backend broadcast subscription enabled:', response);
      },
      error: (err) => {
        console.error('[WS-MODEL] Failed to subscribe to model status broadcast:', err);
      }
    });

    this.subscribeToModelStatusTopic(topic);
    return this.modelStatusUpdates$;
  }

  /**
   * Unsubscribe from model status updates.
   * Also notifies the backend to potentially stop broadcasting.
   */
  unsubscribeFromModelStatus(): void {
    const topic = WebSocketService.MODEL_STATUS_TOPIC;

    // Notify backend to potentially stop broadcasting
    this.http.post(`${backendUrl}/staging-config/broadcast/unsubscribe`, {}).subscribe({
      next: (response: any) => {
        console.debug('[WS-MODEL] Backend broadcast subscription disabled:', response);
      },
      error: (err) => {
        console.error('[WS-MODEL] Failed to unsubscribe from model status broadcast:', err);
      }
    });

    this.unsubscribeFromTopic(topic);
  }

  private subscribeToModelStatusTopic(topic: string): void {
    if (!this.client || this.connectionState.value !== WebSocketConnectionState.CONNECTED) {
      const sub = this.connectionState$.pipe(
        filter(state => state === WebSocketConnectionState.CONNECTED)
      ).subscribe(() => {
        this.doSubscribeModelStatus(topic);
        sub.unsubscribe();
      });
      return;
    }

    this.doSubscribeModelStatus(topic);
  }

  private doSubscribeModelStatus(topic: string): void {
    if (this.subscriptions.has(topic)) {
      return;
    }

    if (!this.client) {
      console.error('Cannot subscribe to model status: client is null');
      return;
    }

    const subscription = this.client.subscribe(topic, (message: IMessage) => {
      this.ngZone.run(() => {
        try {
          const update: ModelStatusUpdate = JSON.parse(message.body);
          this.modelStatusUpdates.next(update);
        } catch (error) {
          console.error('Failed to parse model status update:', error);
        }
      });
    });

    this.subscriptions.set(topic, subscription);
  }

  // ==================== Embedding Subprocess WebSocket ====================

  private static readonly EMBEDDING_SUBPROCESS_TOPIC = '/topic/embedding/subprocess';

  // Embedding subprocess log updates
  private embeddingSubprocessUpdates = new Subject<any>();
  public embeddingSubprocessUpdates$ = this.embeddingSubprocessUpdates.asObservable();

  /**
   * Subscribe to embedding subprocess updates via WebSocket.
   * Provides live updates about embedding model loading and processing.
   */
  subscribeToEmbeddingSubprocess(): Observable<any> {
    const topic = WebSocketService.EMBEDDING_SUBPROCESS_TOPIC;
    this.subscribeToEmbeddingSubprocessTopic(topic);
    return this.embeddingSubprocessUpdates$;
  }

  /**
   * Unsubscribe from embedding subprocess updates.
   */
  unsubscribeFromEmbeddingSubprocess(): void {
    const topic = WebSocketService.EMBEDDING_SUBPROCESS_TOPIC;
    this.unsubscribeFromTopic(topic);
  }

  private subscribeToEmbeddingSubprocessTopic(topic: string): void {
    if (!this.client || this.connectionState.value !== WebSocketConnectionState.CONNECTED) {
      const sub = this.connectionState$.pipe(
        filter(state => state === WebSocketConnectionState.CONNECTED)
      ).subscribe(() => {
        this.doSubscribeEmbeddingSubprocess(topic);
        sub.unsubscribe();
      });
      return;
    }

    this.doSubscribeEmbeddingSubprocess(topic);
  }

  private doSubscribeEmbeddingSubprocess(topic: string): void {
    if (this.subscriptions.has(topic)) {
      return;
    }

    if (!this.client) {
      console.error('Cannot subscribe to embedding subprocess: client is null');
      return;
    }

    const subscription = this.client.subscribe(topic, (message: IMessage) => {
      this.ngZone.run(() => {
        try {
          const update = JSON.parse(message.body);
          this.embeddingSubprocessUpdates.next(update);
        } catch (error) {
          console.error('Failed to parse embedding subprocess update:', error);
        }
      });
    });

    this.subscriptions.set(topic, subscription);
  }

  // ==================== VLM Test WebSocket ====================

  // VLM test progress updates
  private vlmTestUpdates = new Subject<any>();
  public vlmTestUpdates$ = this.vlmTestUpdates.asObservable();

  // VLM test log updates
  private vlmTestLogUpdates = new Subject<IngestLogEntry>();
  public vlmTestLogUpdates$ = this.vlmTestLogUpdates.asObservable();

  /**
   * Subscribe to VLM test progress updates for a specific task.
   */
  subscribeToVlmTest(taskId: string): Observable<any> {
    const topic = `/topic/vlm-test/${taskId}`;
    this.subscribeToVlmTestTopic(topic);
    return this.vlmTestUpdates$.pipe(
      filter(update => update.taskId === taskId)
    );
  }

  /**
   * Unsubscribe from VLM test updates for a specific task.
   */
  unsubscribeFromVlmTest(taskId: string): void {
    const topic = `/topic/vlm-test/${taskId}`;
    this.unsubscribeFromTopic(topic);
  }

  private subscribeToVlmTestTopic(topic: string): void {
    if (!this.client || this.connectionState.value !== WebSocketConnectionState.CONNECTED) {
      const sub = this.connectionState$.pipe(
        filter(state => state === WebSocketConnectionState.CONNECTED)
      ).subscribe(() => {
        this.doSubscribeVlmTest(topic);
        sub.unsubscribe();
      });
      return;
    }

    this.doSubscribeVlmTest(topic);
  }

  private doSubscribeVlmTest(topic: string): void {
    if (this.subscriptions.has(topic)) {
      return;
    }

    if (!this.client) {
      console.error('Cannot subscribe to VLM test: client is null');
      return;
    }

    const subscription = this.client.subscribe(topic, (message: IMessage) => {
      this.ngZone.run(() => {
        try {
          const update = JSON.parse(message.body);
          this.vlmTestUpdates.next(update);
        } catch (error) {
          console.error('Failed to parse VLM test update:', error);
        }
      });
    });

    this.subscriptions.set(topic, subscription);
  }

  /**
   * Subscribe to VLM test log entries for a specific task.
   */
  subscribeToVlmTestLogs(taskId: string): Observable<IngestLogEntry> {
    const topic = `/topic/vlm-test/${taskId}/logs`;
    this.subscribeToVlmTestLogsTopic(topic);
    return this.vlmTestLogUpdates$.pipe(
      filter(entry => entry.taskId === taskId)
    );
  }

  /**
   * Unsubscribe from VLM test log entries for a specific task.
   */
  unsubscribeFromVlmTestLogs(taskId: string): void {
    const topic = `/topic/vlm-test/${taskId}/logs`;
    this.unsubscribeFromTopic(topic);
  }

  private subscribeToVlmTestLogsTopic(topic: string): void {
    if (!this.client || this.connectionState.value !== WebSocketConnectionState.CONNECTED) {
      const sub = this.connectionState$.pipe(
        filter(state => state === WebSocketConnectionState.CONNECTED)
      ).subscribe(() => {
        this.doSubscribeVlmTestLogs(topic);
        sub.unsubscribe();
      });
      return;
    }
    this.doSubscribeVlmTestLogs(topic);
  }

  private doSubscribeVlmTestLogs(topic: string): void {
    if (this.subscriptions.has(topic)) {
      return;
    }

    if (!this.client) {
      console.error('Cannot subscribe to VLM test logs: client is null');
      return;
    }

    const subscription = this.client.subscribe(topic, (message: IMessage) => {
      this.ngZone.run(() => {
        try {
          const logEntry: IngestLogEntry = JSON.parse(message.body);
          this.vlmTestLogUpdates.next(logEntry);
        } catch (error) {
          console.error('Failed to parse VLM test log entry:', error);
        }
      });
    });

    this.subscriptions.set(topic, subscription);
  }

  // ==================== Monitor (Chat Wake-up) WebSocket ====================

  private monitorUpdates = new Subject<MonitorEvent>();
  public monitorUpdates$ = this.monitorUpdates.asObservable();

  /**
   * Subscribe to monitor wake-up events for a specific chat session.
   * Fires when a watched task completes or a scheduled monitor triggers.
   */
  subscribeToMonitor(sessionId: string): Observable<MonitorEvent> {
    const topic = `/topic/monitor/${sessionId}`;
    this.subscribeToMonitorTopic(topic);
    return this.monitorUpdates$.pipe(
      filter(ev => ev.sessionId === sessionId)
    );
  }

  /**
   * Subscribe to all monitor wake-up events (admin / developer-hub view).
   */
  subscribeToAllMonitors(): Observable<MonitorEvent> {
    const topic = '/topic/monitor/all';
    this.subscribeToMonitorTopic(topic);
    return this.monitorUpdates$;
  }

  /**
   * Unsubscribe from a specific session's monitor events.
   */
  unsubscribeFromMonitor(sessionId: string): void {
    this.unsubscribeFromTopic(`/topic/monitor/${sessionId}`);
  }

  unsubscribeFromAllMonitors(): void {
    this.unsubscribeFromTopic('/topic/monitor/all');
  }

  private subscribeToMonitorTopic(topic: string): void {
    if (!this.client || this.connectionState.value !== WebSocketConnectionState.CONNECTED) {
      const sub = this.connectionState$.pipe(
        filter(state => state === WebSocketConnectionState.CONNECTED)
      ).subscribe(() => {
        this.doSubscribeMonitor(topic);
        sub.unsubscribe();
      });
      return;
    }
    this.doSubscribeMonitor(topic);
  }

  private doSubscribeMonitor(topic: string): void {
    if (this.subscriptions.has(topic)) {
      return;
    }
    if (!this.client) {
      console.error('Cannot subscribe to monitor: client is null');
      return;
    }

    const subscription = this.client.subscribe(topic, (message: IMessage) => {
      this.ngZone.run(() => {
        try {
          const event: MonitorEvent = JSON.parse(message.body);
          this.monitorUpdates.next(event);
        } catch (error) {
          console.error('Failed to parse monitor event:', error);
        }
      });
    });

    this.subscriptions.set(topic, subscription);
  }

  // ==================== Enrichment Progress WebSocket ====================

  private static readonly ENRICHMENT_PROGRESS_TOPIC = '/topic/enrichment/progress';

  private enrichmentUpdates = new Subject<EnrichmentProgressUpdate>();
  public enrichmentUpdates$ = this.enrichmentUpdates.asObservable();

  subscribeToEnrichmentProgress(): Observable<EnrichmentProgressUpdate> {
    const topic = WebSocketService.ENRICHMENT_PROGRESS_TOPIC;
    this.subscribeToEnrichmentTopic(topic);
    return this.enrichmentUpdates$;
  }

  unsubscribeFromEnrichmentProgress(): void {
    const topic = WebSocketService.ENRICHMENT_PROGRESS_TOPIC;
    this.unsubscribeFromTopic(topic);
  }

  private subscribeToEnrichmentTopic(topic: string): void {
    if (!this.client || this.connectionState.value !== WebSocketConnectionState.CONNECTED) {
      const sub = this.connectionState$.pipe(
        filter(state => state === WebSocketConnectionState.CONNECTED)
      ).subscribe(() => {
        this.doSubscribeEnrichment(topic);
        sub.unsubscribe();
      });
      return;
    }

    this.doSubscribeEnrichment(topic);
  }

  private doSubscribeEnrichment(topic: string): void {
    if (this.subscriptions.has(topic)) {
      return;
    }

    if (!this.client) {
      console.error('Cannot subscribe to enrichment progress: client is null');
      return;
    }

    const subscription = this.client.subscribe(topic, (message: IMessage) => {
      this.ngZone.run(() => {
        try {
          const update: EnrichmentProgressUpdate = JSON.parse(message.body);
          this.enrichmentUpdates.next(update);
        } catch (error) {
          console.error('Failed to parse enrichment progress update:', error);
        }
      });
    });

    this.subscriptions.set(topic, subscription);
  }

  // ==================== Job Scheduler WebSocket ====================

  private static readonly SCHEDULER_EVENTS_TOPIC = '/topic/scheduler/events';
  private static readonly SCHEDULER_STATUS_TOPIC = '/topic/scheduler/status';
  private static readonly GPU_LIFECYCLE_TOPIC = '/topic/gpu-lifecycle';
  private static readonly SUBPROCESS_HEARTBEAT_TOPIC = '/topic/subprocess/heartbeat';
  private static readonly SUBPROCESS_PHASE_TOPIC = '/topic/subprocess/phase';

  // Scheduler event updates (individual job events: queued, dispatched, blocked, skipped-ahead, etc.)
  private schedulerEvents = new Subject<any>();
  public schedulerEvents$ = this.schedulerEvents.asObservable();

  // GPU lifecycle events (acquire, release, eviction, restoration)
  private gpuLifecycleEvents = new Subject<any>();
  public gpuLifecycleEvents$ = this.gpuLifecycleEvents.asObservable();

  // Subprocess heartbeat memory data
  private subprocessHeartbeats = new Subject<any>();
  public subprocessHeartbeats$ = this.subprocessHeartbeats.asObservable();

  // Subprocess phase transition events
  private subprocessPhases = new Subject<any>();
  public subprocessPhases$ = this.subprocessPhases.asObservable();

  // Scheduler status updates (full queue + running snapshot on state changes)
  private schedulerStatus = new Subject<any>();
  public schedulerStatus$ = this.schedulerStatus.asObservable();

  /**
   * Subscribe to real-time job scheduler event updates via WebSocket.
   * Events include: JOB_QUEUED, JOB_DISPATCHED, JOB_BLOCKED, JOB_SKIPPED_AHEAD,
   * JOB_REORDERED, JOB_COMPLETED, JOB_FAILED, JOB_CANCELLED, etc.
   */
  subscribeToSchedulerEvents(): Observable<any> {
    const topic = WebSocketService.SCHEDULER_EVENTS_TOPIC;
    this.subscribeToSchedulerTopic(topic, this.schedulerEvents);
    return this.schedulerEvents$;
  }

  /**
   * Subscribe to real-time scheduler status snapshots via WebSocket.
   * Status includes full queue, running jobs, and scheduler state on every state change.
   */
  subscribeToSchedulerStatus(): Observable<any> {
    const topic = WebSocketService.SCHEDULER_STATUS_TOPIC;
    this.subscribeToSchedulerTopic(topic, this.schedulerStatus);
    return this.schedulerStatus$;
  }

  /**
   * Unsubscribe from scheduler event updates.
   */
  unsubscribeFromSchedulerEvents(): void {
    this.unsubscribeFromTopic(WebSocketService.SCHEDULER_EVENTS_TOPIC);
  }

  /**
   * Unsubscribe from scheduler status updates.
   */
  unsubscribeFromSchedulerStatus(): void {
    this.unsubscribeFromTopic(WebSocketService.SCHEDULER_STATUS_TOPIC);
  }

  /**
   * Subscribe to GPU lifecycle events (acquire, release, eviction, restoration).
   */
  subscribeToGpuLifecycleEvents(): Observable<any> {
    this.subscribeToSchedulerTopic(WebSocketService.GPU_LIFECYCLE_TOPIC, this.gpuLifecycleEvents);
    return this.gpuLifecycleEvents$;
  }

  unsubscribeFromGpuLifecycleEvents(): void {
    this.unsubscribeFromTopic(WebSocketService.GPU_LIFECYCLE_TOPIC);
  }

  /**
   * Subscribe to subprocess heartbeat memory data.
   */
  subscribeToSubprocessHeartbeats(): Observable<any> {
    this.subscribeToSchedulerTopic(WebSocketService.SUBPROCESS_HEARTBEAT_TOPIC, this.subprocessHeartbeats);
    return this.subprocessHeartbeats$;
  }

  unsubscribeFromSubprocessHeartbeats(): void {
    this.unsubscribeFromTopic(WebSocketService.SUBPROCESS_HEARTBEAT_TOPIC);
  }

  /**
   * Subscribe to subprocess phase transition events with duration data.
   */
  subscribeToSubprocessPhases(): Observable<any> {
    this.subscribeToSchedulerTopic(WebSocketService.SUBPROCESS_PHASE_TOPIC, this.subprocessPhases);
    return this.subprocessPhases$;
  }

  unsubscribeFromSubprocessPhases(): void {
    this.unsubscribeFromTopic(WebSocketService.SUBPROCESS_PHASE_TOPIC);
  }

  private subscribeToSchedulerTopic(topic: string, subject: Subject<any>): void {
    if (!this.client || this.connectionState.value !== WebSocketConnectionState.CONNECTED) {
      const sub = this.connectionState$.pipe(
        filter(state => state === WebSocketConnectionState.CONNECTED)
      ).subscribe(() => {
        this.doSubscribeScheduler(topic, subject);
        sub.unsubscribe();
      });
      return;
    }

    this.doSubscribeScheduler(topic, subject);
  }

  private doSubscribeScheduler(topic: string, subject: Subject<any>): void {
    if (this.subscriptions.has(topic)) {
      return;
    }

    if (!this.client) {
      console.error('Cannot subscribe to scheduler topic: client is null');
      return;
    }

    const subscription = this.client.subscribe(topic, (message: IMessage) => {
      this.ngZone.run(() => {
        try {
          const update = JSON.parse(message.body);
          subject.next(update);
        } catch (error) {
          console.error('Failed to parse scheduler update:', error);
        }
      });
    });

    this.subscriptions.set(topic, subscription);
  }

  // ==================== Crawl Progress WebSocket ====================

  private static readonly CRAWL_PROGRESS_TOPIC = '/topic/crawl/progress';
  private static readonly CRAWL_COMPLETE_TOPIC = '/topic/crawl/complete';

  private crawlProgress = new Subject<any>();
  public crawlProgress$ = this.crawlProgress.asObservable();
  private crawlComplete = new Subject<any>();
  public crawlComplete$ = this.crawlComplete.asObservable();

  subscribeToCrawlProgress(): Observable<any> {
    this.subscribeToSchedulerTopic(WebSocketService.CRAWL_PROGRESS_TOPIC, this.crawlProgress);
    return this.crawlProgress$;
  }

  subscribeToCrawlComplete(): Observable<any> {
    this.subscribeToSchedulerTopic(WebSocketService.CRAWL_COMPLETE_TOPIC, this.crawlComplete);
    return this.crawlComplete$;
  }

  unsubscribeFromCrawlProgress(): void {
    this.unsubscribeFromTopic(WebSocketService.CRAWL_PROGRESS_TOPIC);
    this.unsubscribeFromTopic(WebSocketService.CRAWL_COMPLETE_TOPIC);
  }

  // ==================== Note Sync WebSocket ====================

  private static readonly SYNC_ALL_TOPIC = '/topic/sync/all';

  private syncProgress = new Subject<any>();
  public syncProgress$ = this.syncProgress.asObservable();

  subscribeToSyncProgress(): Observable<any> {
    this.subscribeToSchedulerTopic(WebSocketService.SYNC_ALL_TOPIC, this.syncProgress);
    return this.syncProgress$;
  }

  unsubscribeFromSyncProgress(): void {
    this.unsubscribeFromTopic(WebSocketService.SYNC_ALL_TOPIC);
  }

  // ==================== Adaptive Batching Audit WebSocket ====================

  private static readonly ADAPTIVE_AUDIT_TOPIC = '/topic/adaptive/audit';
  private static readonly ADAPTIVE_STATUS_TOPIC = '/topic/adaptive/status';

  private adaptiveAudit = new Subject<any>();
  public adaptiveAudit$ = this.adaptiveAudit.asObservable();
  private adaptiveStatus = new Subject<any>();
  public adaptiveStatus$ = this.adaptiveStatus.asObservable();

  subscribeToAdaptiveAudit(): Observable<any> {
    this.subscribeToSchedulerTopic(WebSocketService.ADAPTIVE_AUDIT_TOPIC, this.adaptiveAudit);
    return this.adaptiveAudit$;
  }

  subscribeToAdaptiveStatus(): Observable<any> {
    this.subscribeToSchedulerTopic(WebSocketService.ADAPTIVE_STATUS_TOPIC, this.adaptiveStatus);
    return this.adaptiveStatus$;
  }

  unsubscribeFromAdaptiveAudit(): void {
    this.unsubscribeFromTopic(WebSocketService.ADAPTIVE_AUDIT_TOPIC);
    this.unsubscribeFromTopic(WebSocketService.ADAPTIVE_STATUS_TOPIC);
  }

  // ==================== Common Methods ====================

  private subscribeToTopic(topic: string): void {
    if (!this.client || this.connectionState.value !== WebSocketConnectionState.CONNECTED) {
      // Wait for connection and then subscribe
      const sub = this.connectionState$.pipe(
        filter(state => state === WebSocketConnectionState.CONNECTED)
      ).subscribe(() => {
        this.doSubscribe(topic);
        sub.unsubscribe();
      });
      return;
    }

    this.doSubscribe(topic);
  }

  private doSubscribe(topic: string): void {
    if (this.subscriptions.has(topic)) {
      return;
    }

    if (!this.client) {
      console.error('Cannot subscribe: client is null');
      return;
    }

    const subscription = this.client.subscribe(topic, (message: IMessage) => {
      // Run inside Angular zone to trigger change detection
      this.ngZone.run(() => {
        try {
          const update: IngestProgressUpdate = JSON.parse(message.body);
          this.progressUpdates.next(update);
        } catch (error) {
          console.error('Failed to parse progress update:', error);
        }
      });
    });

    this.subscriptions.set(topic, subscription);
  }

  private unsubscribeFromTopic(topic: string): void {
    const subscription = this.subscriptions.get(topic);
    if (subscription) {
      subscription.unsubscribe();
      this.subscriptions.delete(topic);
    }
  }

  private getWebSocketUrl(): string {
    // Get the base URL and construct WebSocket endpoint
    const baseUrl = backendUrl.replace('/api', '');
    // The STOMP endpoint is registered at /ws (not /ws/ingest)
    // The /ingest topic subscription is handled via STOMP, not the endpoint URL
    return `${baseUrl}/ws`;
  }

  ngOnDestroy(): void {
    this.disconnect();
  }
}
