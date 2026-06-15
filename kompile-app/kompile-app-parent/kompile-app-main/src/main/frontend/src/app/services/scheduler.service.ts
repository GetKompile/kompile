import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

// ==================== Interfaces ====================

export interface ScheduledJobView {
  jobId: string;
  jobType: string;
  description: string;
  state: string;
  currentPhase: string;
  priority: number;
  gpuHeld: boolean;
  queuedAt: string;
  startedAt: string;
  completedAt: string;
  durationMs: number;
  waitMs: number;
  profileName: string;
  requiresGpu: boolean;
  peakGpuMemoryMb: number;
  metadata: Record<string, any>;
  externalRef: string;
  externallyDelegated: boolean;
  blockedReason: string;
  cancelReason: string;
}

export interface JobHistoryEntry {
  jobId: string;
  jobType: string;
  description: string;
  state: string;
  priority: number;
  requiresGpu: boolean;
  peakGpuMemoryMb: number;
  queuedAt: string;
  startedAt: string;
  completedAt: string;
  durationMs: number;
  waitMs: number;
  error: string;
  cancelReason: string;
  blockedReason: string;
  phases: PhaseHistoryEntry[];
}

export interface PhaseHistoryEntry {
  phaseName: string;
  timestamp: string;
  requiresGpu: boolean;
}

export interface PhaseResourceProfile {
  phaseName: string;
  requiresGpu: boolean;
  gpuMemoryBytes: number;
  estimatedDurationSeconds: number;
  canYieldGpu: boolean;
}

export interface JobResourceProfile {
  serviceType: string;
  displayName: string;
  requiresGpu: boolean;
  peakGpuMemoryBytes: number;
  estimatedHeapBytes: number;
  concurrentAllowed: boolean;
  maxConcurrent: number;
  phaseProfiles: PhaseResourceProfile[];
  conflictsWith: string[];
  batchableWith: string[];
}

export interface SchedulerStatus {
  enabled: boolean;
  running: boolean;
  algorithm: string;
  phaseAwareYield: boolean;
  queueDepth: number;
  queueCapacity: number;
  runningCount: number;
  totalSubmitted: number;
  totalCompleted: number;
  totalFailed: number;
  totalCancelled: number;
  runningByType: Record<string, number>;
  queuedByType: Record<string, number>;
  maxConcurrentByType: Record<string, number>;
  externalSchedulerMode?: string;
  externalSchedulerEnabled?: boolean;
  externalSchedulerAvailable?: boolean;
  externallyDelegatedCount?: number;
}

export interface SchedulerDashboard {
  status: SchedulerStatus;
  queue: ScheduledJobView[];
  running: ScheduledJobView[];
  recentHistory: JobHistoryEntry[];
  recentEvents?: SchedulerEvent[];
  stats: Record<string, any>;
  profiles: Record<string, JobResourceProfile>;
  config: any;
  externalScheduler: any[];
}

export interface SchedulerEvent {
  eventType: string;
  timestamp: string;
  jobId?: string;
  jobType?: string;
  currentPhase?: string;
  queueDepth: number;
  runningCount: number;
  description?: string;
  // Event-specific fields flattened from data map
  priority?: number;
  durationMs?: number;
  error?: string;
  cancelReason?: string;
  blockedReason?: string;
  blockedJobId?: string;
  blockedJobType?: string;
  oldPriority?: number;
  newPriority?: number;
  previousPhase?: string;
  requiresGpu?: boolean;
  rejectedJobId?: string;
  blockedCount?: number;
  skippedCount?: number;
}

// ==================== Service ====================

@Injectable({
  providedIn: 'root'
})
export class SchedulerService {
  private readonly apiUrl = `${backendUrl}/scheduler`;

  private eventLog: SchedulerEvent[] = [];
  private readonly maxEventLog = 200;

  constructor(private http: HttpClient) {}

  // ==================== Dashboard ====================

  getDashboard(): Observable<SchedulerDashboard> {
    return this.http.get<SchedulerDashboard>(`${this.apiUrl}/dashboard`);
  }

  // ==================== Actions ====================

  cancelJob(jobId: string, externallyDelegated: boolean = false): Observable<any> {
    const endpoint = externallyDelegated ? 'cancel-external' : 'cancel';
    return this.http.post(`${this.apiUrl}/jobs/${jobId}/${endpoint}`, {});
  }

  promoteJob(jobId: string, priority: number): Observable<any> {
    const params = new HttpParams().set('priority', priority.toString());
    return this.http.post(`${this.apiUrl}/jobs/${jobId}/promote`, {}, { params });
  }

  // ==================== History ====================

  getHistory(limit: number = 50, type?: string, state?: string): Observable<JobHistoryEntry[]> {
    let params = new HttpParams().set('limit', limit.toString());
    if (type) params = params.set('type', type);
    if (state) params = params.set('state', state);
    return this.http.get<JobHistoryEntry[]>(`${this.apiUrl}/history`, { params });
  }

  // ==================== Config ====================

  updateConfig(config: any): Observable<any> {
    return this.http.put(`${this.apiUrl}/config`, config);
  }

  resetConfig(): Observable<any> {
    return this.http.post(`${this.apiUrl}/config/reset`, {});
  }

  // ==================== Events ====================

  getRecentEvents(limit: number = 100): Observable<{ events: SchedulerEvent[], totalEventCount: number }> {
    const params = new HttpParams().set('limit', limit.toString());
    return this.http.get<{ events: SchedulerEvent[], totalEventCount: number }>(`${this.apiUrl}/events`, { params });
  }

  // ==================== Event Log (in-memory) ====================

  addEvent(event: SchedulerEvent): void {
    this.eventLog = [event, ...this.eventLog].slice(0, this.maxEventLog);
  }

  clearEvents(): void {
    this.eventLog = [];
  }

  // ==================== Status / Queue / Running ====================

  getStatus(): Observable<any> {
    return this.http.get(`${this.apiUrl}/status`);
  }

  getQueue(): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/queue`);
  }

  getRunning(): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/running`);
  }

  getJob(jobId: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/jobs/${jobId}`);
  }

  getProfiles(): Observable<any> {
    return this.http.get(`${this.apiUrl}/profiles`);
  }

  getHistoryStats(): Observable<any> {
    return this.http.get(`${this.apiUrl}/history/stats`);
  }

  getExternalStatus(): Observable<any> {
    return this.http.get(`${this.apiUrl}/external/status`);
  }

  getExternalModes(): Observable<any> {
    return this.http.get(`${this.apiUrl}/external/modes`);
  }

  // ==================== Utilities ====================

  formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    const min = Math.floor(ms / 60000);
    const sec = Math.floor((ms % 60000) / 1000);
    return `${min}m ${sec}s`;
  }

  getStateClass(state: string): string {
    switch (state) {
      case 'COMPLETED': return 'status-completed';
      case 'FAILED': return 'status-failed';
      case 'CANCELLED': return 'status-cancelled';
      case 'RUNNING': return 'status-running';
      case 'DISPATCHED': return 'status-running';
      case 'ACQUIRING': return 'status-running';
      case 'QUEUED': return 'status-queued';
      case 'BLOCKED': return 'status-blocked';
      case 'PHASE_YIELDING': return 'status-yielding';
      default: return '';
    }
  }

  getEventClass(eventType: string): string {
    switch (eventType) {
      case 'JOB_COMPLETED': return 'event-success';
      case 'JOB_FAILED': return 'event-error';
      case 'JOB_CANCELLED': return 'event-warning';
      case 'JOB_BLOCKED': return 'event-blocked';
      case 'JOB_SKIPPED_AHEAD': return 'event-info';
      case 'JOB_REORDERED': return 'event-info';
      case 'JOB_PROMOTED': return 'event-info';
      case 'QUEUE_FULL': return 'event-error';
      case 'JOB_QUEUED': return 'event-queued';
      case 'JOB_DISPATCHED': return 'event-dispatched';
      case 'JOB_PHASE_TRANSITION': return 'event-phase';
      case 'SCHEDULER_STARTED': return 'event-lifecycle';
      case 'SCHEDULER_STOPPED': return 'event-lifecycle';
      default: return '';
    }
  }

  formatJobType(type: string): string {
    const map: Record<string, string> = {
      'ingest': 'Document Ingest',
      'vectorPopulation': 'Vector Population',
      'vlm': 'VLM Test',
      'training': 'Training',
      'llmServing': 'LLM Serving',
      'unifiedCrawl': 'Unified Crawl',
      'crawl': 'Crawl',
      'modelInit': 'Model Init',
      'embedding': 'Embedding'
    };
    return map[type] || type;
  }

  formatEventType(eventType: string): string {
    const map: Record<string, string> = {
      'JOB_QUEUED': 'Queued',
      'JOB_DISPATCHED': 'Dispatched',
      'JOB_PHASE_TRANSITION': 'Phase Change',
      'JOB_COMPLETED': 'Completed',
      'JOB_FAILED': 'Failed',
      'JOB_CANCELLED': 'Cancelled',
      'JOB_PROMOTED': 'Promoted',
      'JOB_BLOCKED': 'Blocked',
      'JOB_SKIPPED_AHEAD': 'Skip-Ahead',
      'JOB_REORDERED': 'Reordered',
      'QUEUE_FULL': 'Queue Full',
      'SCHEDULER_STARTED': 'Scheduler Started',
      'SCHEDULER_STOPPED': 'Scheduler Stopped'
    };
    return map[eventType] || eventType;
  }

  buildEventDescription(event: SchedulerEvent): string {
    switch (event.eventType) {
      case 'JOB_QUEUED':
        return `Job "${event.description || event.jobId}" (${this.formatJobType(event.jobType || '')}) queued at priority ${event.priority ?? '?'}`;
      case 'JOB_DISPATCHED':
        return `Job "${event.description || event.jobId}" dispatched for execution`;
      case 'JOB_COMPLETED':
        return `Job "${event.description || event.jobId}" completed in ${this.formatDuration(event.durationMs || 0)}`;
      case 'JOB_FAILED':
        return `Job "${event.description || event.jobId}" failed: ${event.error || 'Unknown error'}`;
      case 'JOB_CANCELLED':
        return `Job "${event.jobId}" cancelled: ${event.cancelReason || 'No reason given'}`;
      case 'JOB_BLOCKED':
        return `Job "${event.jobId}" blocked: ${event.blockedReason || 'Unknown'}`;
      case 'JOB_SKIPPED_AHEAD':
        return `Job "${event.jobId}" (${this.formatJobType(event.jobType || '')}) skipped ahead of blocked "${event.blockedJobId}" (${event.blockedReason})`;
      case 'JOB_REORDERED':
        return `Queue reordered: ${event.blockedCount} blocked, ${event.skippedCount} dispatched via skip-ahead`;
      case 'JOB_PROMOTED':
        return `Job "${event.jobId}" priority changed: ${event.oldPriority} -> ${event.newPriority}`;
      case 'JOB_PHASE_TRANSITION':
        return `Job "${event.jobId}" phase: ${event.previousPhase || '?'} -> ${event.currentPhase}${event.requiresGpu ? ' (GPU)' : ' (CPU)'}`;
      case 'QUEUE_FULL':
        return `Queue full! Job "${event.rejectedJobId || event.jobId || '?'}" (${this.formatJobType(event.jobType || '')}) rejected`;
      case 'SCHEDULER_STARTED':
        return 'Job scheduler started';
      case 'SCHEDULER_STOPPED':
        return 'Job scheduler stopped';
      default:
        return event.eventType;
    }
  }
}
