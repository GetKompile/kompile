import { Component, OnDestroy, OnInit, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { Subscription } from 'rxjs';
import { MonitorService } from '../../services/monitor.service';
import { WebSocketService } from '../../services/websocket.service';
import { MonitorEvent, MonitorRegistration } from '../../models/monitor-models';

type ScheduleKind = 'once' | 'cron';

@Component({
  selector: 'app-monitors-manager',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSnackBarModule,
    MatTableModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    MatSlideToggleModule
  ],
  templateUrl: './monitors-manager.component.html',
  styleUrls: ['./monitors-manager.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class MonitorsManagerComponent implements OnInit, OnDestroy {

  loading = false;
  includeNonActive = false;

  monitors: MonitorRegistration[] = [];
  recentEvents: MonitorEvent[] = [];

  // Schedule form
  scheduleKind: ScheduleKind = 'once';
  sessionId = '';
  description = '';
  payload = '';
  delaySeconds = 300;
  cronExpression = '0 * * * * ?';

  // Watch-task form
  watchSessionId = '';
  watchTaskId = '';
  watchDescription = '';
  watchPayload = '';

  readonly tableColumns = [
    'type', 'sessionId', 'status', 'target', 'description', 'fireCount', 'actions'
  ];

  private sub: Subscription | null = null;

  constructor(
    private monitorService: MonitorService,
    private webSocketService: WebSocketService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.refresh();
    this.webSocketService.connect();
    this.sub = this.webSocketService.subscribeToAllMonitors().subscribe((event: MonitorEvent) => {
      this.recentEvents = [event, ...this.recentEvents].slice(0, 20);
      // A fire likely changes status — reload in background
      this.refresh();
      this.cdr.markForCheck();
    });
  }

  ngOnDestroy(): void {
    if (this.sub) {
      this.sub.unsubscribe();
      this.sub = null;
    }
    this.webSocketService.unsubscribeFromAllMonitors();
  }

  refresh(): void {
    this.loading = true;
    this.cdr.markForCheck();
    this.monitorService.list(undefined, this.includeNonActive).subscribe({
      next: (list) => {
        this.monitors = list;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.monitors = [];
        this.loading = false;
        this.cdr.markForCheck();
        this.snackBar.open('Failed to load monitors: ' + (err.error?.message || err.message), 'OK', { duration: 5000 });
      }
    });
  }

  schedule(): void {
    if (!this.sessionId.trim()) {
      this.snackBar.open('Chat session ID is required', 'OK', { duration: 3000 });
      return;
    }
    const description = this.description.trim() || undefined;
    const payload = this.payload.trim() || undefined;
    if (this.scheduleKind === 'once') {
      const delay = Math.max(1, this.delaySeconds || 0);
      const fireAtEpochMs = Date.now() + delay * 1000;
      this.monitorService.scheduleOnce({
        sessionId: this.sessionId.trim(),
        fireAtEpochMs,
        description,
        payload
      }).subscribe({
        next: () => {
          this.snackBar.open('One-shot monitor scheduled', 'OK', { duration: 3000 });
          this.refresh();
        },
        error: (err) => this.snackBar.open('Schedule failed: ' + (err.error?.error || err.message), 'OK', { duration: 5000 })
      });
    } else {
      if (!this.cronExpression.trim()) {
        this.snackBar.open('Cron expression is required', 'OK', { duration: 3000 });
        return;
      }
      this.monitorService.scheduleCron({
        sessionId: this.sessionId.trim(),
        cronExpression: this.cronExpression.trim(),
        description,
        payload
      }).subscribe({
        next: () => {
          this.snackBar.open('Cron monitor scheduled', 'OK', { duration: 3000 });
          this.refresh();
        },
        error: (err) => this.snackBar.open('Schedule failed: ' + (err.error?.error || err.message), 'OK', { duration: 5000 })
      });
    }
  }

  watchTask(): void {
    if (!this.watchSessionId.trim() || !this.watchTaskId.trim()) {
      this.snackBar.open('Session ID and task ID are required', 'OK', { duration: 3000 });
      return;
    }
    this.monitorService.watchTask({
      sessionId: this.watchSessionId.trim(),
      taskId: this.watchTaskId.trim(),
      description: this.watchDescription.trim() || undefined,
      payload: this.watchPayload.trim() || undefined
    }).subscribe({
      next: () => {
        this.snackBar.open('Task watch registered', 'OK', { duration: 3000 });
        this.refresh();
      },
      error: (err) => this.snackBar.open('Watch failed: ' + (err.error?.error || err.message), 'OK', { duration: 5000 })
    });
  }

  cancel(monitor: MonitorRegistration): void {
    this.monitorService.cancel(monitor.monitorId).subscribe({
      next: () => {
        this.snackBar.open('Monitor cancelled', 'OK', { duration: 3000 });
        this.refresh();
      },
      error: (err) => this.snackBar.open('Cancel failed: ' + (err.error?.error || err.message), 'OK', { duration: 5000 })
    });
  }

  target(m: MonitorRegistration): string {
    if (m.type === 'TASK_COMPLETION') return m.taskId || '-';
    if (m.type === 'SCHEDULED_ONCE') return m.fireAtEpochMs ? new Date(m.fireAtEpochMs).toLocaleString() : '-';
    if (m.type === 'SCHEDULED_CRON') return m.cronExpression || '-';
    return '-';
  }
}
