import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { Subject, interval } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import {
  SchedulerService, SchedulerDashboard, SchedulerEvent,
  ScheduledJobView, JobHistoryEntry, SchedulerStatus
} from '../../../services/scheduler.service';
import { WebSocketService } from '../../../services/websocket.service';
import { LogSourceType } from '../../subprocess-logs/subprocess-logs.component';

@Component({
  standalone: false,
  selector: 'app-scheduler-dashboard',
  templateUrl: './scheduler-dashboard.component.html',
  styleUrls: ['./scheduler-dashboard.component.css']
})
export class SchedulerDashboardComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  dashboard: SchedulerDashboard | null = null;
  eventLog: SchedulerEvent[] = [];
  loading = true;
  error: string | null = null;

  // View mode
  activeTab: 'overview' | 'queue' | 'running' | 'history' | 'events' | 'logs' | 'config' = 'overview';

  // Subprocess log viewer
  selectedLogJob: ScheduledJobView | null = null;
  selectedLogSource: LogSourceType = 'ingest';
  selectedLogTaskId: string = '';

  // History filters
  historyTypeFilter = '';
  historyStateFilter = '';
  historyLimit = 50;

  // History detail
  selectedHistoryEntry: JobHistoryEntry | null = null;

  // Total event count (from server, may exceed local eventLog size)
  totalEventCount = 0;

  // Auto-refresh
  autoRefresh = true;
  refreshInterval = 5;

  // GPU lifecycle events
  gpuEvents: any[] = [];

  // Subprocess heartbeat data (latest per task)
  subprocessHeartbeats: Map<string, any> = new Map();

  // Subprocess phase transitions (recent)
  phaseTransitions: any[] = [];

  // Config editing
  editableConfig: any = null;
  configSaving = false;
  configMessage: string | null = null;

  constructor(
    public schedulerService: SchedulerService,
    private wsService: WebSocketService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadDashboard();
    this.loadRecentEvents();
    this.subscribeToWebSocket();
    this.startAutoRefresh();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.wsService.unsubscribeFromSchedulerEvents();
    this.wsService.unsubscribeFromSchedulerStatus();
    this.wsService.unsubscribeFromGpuLifecycleEvents();
    this.wsService.unsubscribeFromSubprocessHeartbeats();
    this.wsService.unsubscribeFromSubprocessPhases();
  }

  // ==================== Data Loading ====================

  loadDashboard(): void {
    this.loading = true;
    this.schedulerService.getDashboard().pipe(takeUntil(this.destroy$)).subscribe({
      next: d => {
        this.dashboard = d;
        this.loading = false;
        this.error = null;
        // Initialize editable config on first load
        if (!this.editableConfig && d.config) {
          this.editableConfig = JSON.parse(JSON.stringify(d.config));
        }
        // Merge bundled recentEvents into event log if WS hasn't provided fresher data
        if (d.recentEvents && d.recentEvents.length > 0 && this.eventLog.length === 0) {
          this.eventLog = d.recentEvents.slice(0, 200);
        }
        this.cdr.markForCheck();
      },
      error: err => {
        this.error = 'Failed to load scheduler dashboard';
        this.loading = false;
        this.cdr.markForCheck();
      }
    });
  }

  loadHistory(): void {
    this.schedulerService.getHistory(
      this.historyLimit,
      this.historyTypeFilter || undefined,
      this.historyStateFilter || undefined
    ).pipe(takeUntil(this.destroy$)).subscribe({
      next: history => {
        if (this.dashboard) {
          this.dashboard = { ...this.dashboard, recentHistory: history };
        }
        this.cdr.markForCheck();
      },
      error: err => console.error('Failed to load history', err)
    });
  }

  loadRecentEvents(): void {
    this.schedulerService.getRecentEvents(200).pipe(takeUntil(this.destroy$)).subscribe({
      next: result => {
        if (result.events && result.events.length > 0) {
          this.eventLog = result.events;
        }
        this.totalEventCount = result.totalEventCount || this.eventLog.length;
        this.cdr.markForCheck();
      },
      error: err => console.error('Failed to load recent events', err)
    });
  }

  // ==================== WebSocket ====================

  subscribeToWebSocket(): void {
    this.wsService.subscribeToSchedulerEvents()
      .pipe(takeUntil(this.destroy$))
      .subscribe(event => {
        const schedulerEvent = event as SchedulerEvent;
        this.eventLog = [schedulerEvent, ...this.eventLog].slice(0, 200);
        this.schedulerService.addEvent(schedulerEvent);
        this.cdr.markForCheck();
      });

    this.wsService.subscribeToSchedulerStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe(payload => {
        if (this.dashboard) {
          // The WS payload has getStatus() scalar fields + queue/running arrays + timestamp.
          // The payload has TWO "running" keys: the boolean (scheduler liveness) from getStatus()
          // and the array (running jobs snapshot). The array overwrites the boolean in the raw object.
          // We extract the array fields, then reconstruct status with the boolean preserved.
          const data = payload as any;
          const statusQueue = Array.isArray(data.queue) ? data.queue : this.dashboard.queue;
          const statusRunning = Array.isArray(data.running) ? data.running : this.dashboard.running;

          // Build status from non-array fields, preserving the boolean 'running' from current status
          // since the WS payload's 'running' key is overwritten by the array
          const { queue: _q, running: _r, timestamp: _t, ...statusFields } = data;
          const updatedStatus: SchedulerStatus = {
            ...this.dashboard.status,
            ...statusFields,
            running: typeof data.running === 'boolean' ? data.running : this.dashboard.status.running
          };

          this.dashboard = {
            ...this.dashboard,
            status: updatedStatus,
            queue: statusQueue,
            running: statusRunning
          };
          this.cdr.markForCheck();
        }
      });

    // GPU lifecycle events
    this.wsService.subscribeToGpuLifecycleEvents()
      .pipe(takeUntil(this.destroy$))
      .subscribe(event => {
        this.gpuEvents = [event, ...this.gpuEvents].slice(0, 50);
        this.cdr.markForCheck();
      });

    // Subprocess heartbeat memory data (keep latest per task)
    this.wsService.subscribeToSubprocessHeartbeats()
      .pipe(takeUntil(this.destroy$))
      .subscribe(heartbeat => {
        this.subprocessHeartbeats.set(heartbeat.taskId, heartbeat);
        this.cdr.markForCheck();
      });

    // Subprocess phase transitions
    this.wsService.subscribeToSubprocessPhases()
      .pipe(takeUntil(this.destroy$))
      .subscribe(phase => {
        this.phaseTransitions = [phase, ...this.phaseTransitions].slice(0, 50);
        this.cdr.markForCheck();
      });
  }

  // ==================== Auto-Refresh ====================

  startAutoRefresh(): void {
    interval(this.refreshInterval * 1000)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        if (this.autoRefresh) {
          this.loadDashboard();
        }
      });
  }

  toggleAutoRefresh(): void {
    this.autoRefresh = !this.autoRefresh;
  }

  // ==================== Job Actions ====================

  cancelJob(jobId: string, externallyDelegated: boolean = false): void {
    this.schedulerService.cancelJob(jobId, externallyDelegated).subscribe({
      next: () => this.loadDashboard(),
      error: err => console.error('Failed to cancel job', err)
    });
  }

  promoteJob(jobId: string): void {
    const newPriority = prompt('Enter new priority (higher = sooner):', '100');
    if (newPriority !== null) {
      const p = parseInt(newPriority, 10);
      if (!isNaN(p)) {
        this.schedulerService.promoteJob(jobId, p).subscribe({
          next: () => this.loadDashboard(),
          error: err => console.error('Failed to promote job', err)
        });
      }
    }
  }

  // ==================== History Detail ====================

  showHistoryDetail(entry: JobHistoryEntry): void {
    this.selectedHistoryEntry = entry;
    this.activeTab = 'history';
  }

  clearHistoryDetail(): void {
    this.selectedHistoryEntry = null;
  }

  // ==================== Config Editing ====================

  saveConfig(): void {
    if (!this.editableConfig || this.configSaving) return;
    this.configSaving = true;
    this.configMessage = null;
    this.schedulerService.updateConfig(this.editableConfig).subscribe({
      next: (saved) => {
        this.editableConfig = JSON.parse(JSON.stringify(saved));
        if (this.dashboard) {
          this.dashboard = { ...this.dashboard, config: saved };
        }
        this.configMessage = 'Configuration saved successfully.';
        this.configSaving = false;
        this.cdr.markForCheck();
        setTimeout(() => { this.configMessage = null; this.cdr.markForCheck(); }, 3000);
      },
      error: (err) => {
        this.configMessage = 'Failed to save configuration: ' + (err.error?.message || err.message || 'Unknown error');
        this.configSaving = false;
        this.cdr.markForCheck();
      }
    });
  }

  resetConfig(): void {
    this.configMessage = null;
    this.schedulerService.resetConfig().subscribe({
      next: (defaults) => {
        this.editableConfig = JSON.parse(JSON.stringify(defaults));
        if (this.dashboard) {
          this.dashboard = { ...this.dashboard, config: defaults };
        }
        this.configMessage = 'Configuration reset to defaults.';
        this.cdr.markForCheck();
        setTimeout(() => { this.configMessage = null; this.cdr.markForCheck(); }, 3000);
      },
      error: (err) => {
        this.configMessage = 'Failed to reset configuration: ' + (err.error?.message || err.message || 'Unknown error');
        this.cdr.markForCheck();
      }
    });
  }

  // ==================== Filter Changes ====================

  onHistoryFilterChange(): void {
    this.loadHistory();
  }

  // ==================== Utilities ====================

  getQueuePosition(job: ScheduledJobView): number {
    if (!this.dashboard) return -1;
    return this.dashboard.queue.findIndex(q => q.jobId === job.jobId) + 1;
  }

  get queuedJobs(): ScheduledJobView[] {
    return this.dashboard?.queue || [];
  }

  get runningJobs(): ScheduledJobView[] {
    return this.dashboard?.running || [];
  }

  get historyEntries(): JobHistoryEntry[] {
    return this.dashboard?.recentHistory || [];
  }

  get status(): SchedulerStatus | null {
    return this.dashboard?.status || null;
  }

  trackByJobId(_: number, job: ScheduledJobView): string {
    return job.jobId;
  }

  trackByHistoryId(_: number, entry: JobHistoryEntry): string {
    return entry.jobId;
  }

  trackByEventTs(_: number, event: SchedulerEvent): string {
    return event.timestamp + (event.jobId || '');
  }

  clearEventLog(): void {
    this.eventLog = [];
    this.schedulerService.clearEvents();
  }

  getJobTypeOptions(): string[] {
    return ['ingest', 'vectorPopulation', 'vlm', 'training', 'llmServing', 'unifiedCrawl', 'crawl', 'modelInit', 'embedding'];
  }

  objectEntries(obj: Record<string, any> | undefined): [string, any][] {
    return obj ? Object.entries(obj) : [];
  }

  hasEntries(obj: Record<string, any> | undefined): boolean {
    return !!obj && Object.keys(obj).length > 0;
  }

  jsonStringify(obj: any): string {
    if (obj === null || obj === undefined) return '';
    if (typeof obj === 'string') return obj;
    return JSON.stringify(obj);
  }

  formatBytes(bytes: number): string {
    if (!bytes || bytes === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return (bytes / Math.pow(1024, i)).toFixed(i > 1 ? 1 : 0) + ' ' + units[i];
  }

  getHeartbeatForJob(jobId: string): any {
    return this.subprocessHeartbeats.get(jobId);
  }


  // ==================== Subprocess Log Viewing ====================

  /**
   * Map scheduler job type to subprocess log source type.
   */
  private jobTypeToLogSource(jobType: string): LogSourceType {
    switch (jobType) {
      case 'ingest': return 'ingest';
      case 'vectorPopulation': return 'vector-population';
      case 'vlm': return 'vlm-test';
      case 'training': return 'training';
      case 'llmServing': return 'serving';
      case 'embedding': return 'embedding';
      default: return 'ingest';
    }
  }

  /**
   * Show subprocess logs for a job found by ID (used by dropdown).
   */
  viewJobLogsByJobId(jobId: string): void {
    if (!jobId) return;
    const job = this.allActiveJobs.find(j => j.jobId === jobId);
    if (job) this.viewJobLogs(job);
  }

  /**
   * Show subprocess logs for a specific job.
   */
  viewJobLogs(job: ScheduledJobView): void {
    this.selectedLogJob = job;
    this.selectedLogSource = this.jobTypeToLogSource(job.jobType);
    this.selectedLogTaskId = job.jobId;
    this.activeTab = 'logs';
  }

  /**
   * Clear subprocess log selection.
   */
  clearLogSelection(): void {
    this.selectedLogJob = null;
    this.selectedLogTaskId = '';
    this.selectedLogSource = 'ingest';
  }

  /**
   * Get all jobs (running + queued) for the log selector.
   */
  get allActiveJobs(): ScheduledJobView[] {
    return [...this.runningJobs, ...this.queuedJobs];
  }
}
