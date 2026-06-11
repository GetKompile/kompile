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
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatBadgeModule } from '@angular/material/badge';
import { MatDividerModule } from '@angular/material/divider';
import { Subscription } from 'rxjs';
import {
  EnforcerService,
  EnforcerSession,
  EnforcerProcess,
  EnforcerEvent,
  CreateSessionRequest
} from '../../services/enforcer.service';

@Component({
  selector: 'app-enforcer-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatChipsModule,
    MatSlideToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatExpansionModule,
    MatBadgeModule,
    MatDividerModule
  ],
  templateUrl: './enforcer-dashboard.component.html',
  styleUrls: ['./enforcer-dashboard.component.css']
})
export class EnforcerDashboardComponent implements OnInit, OnDestroy {

  sessions: EnforcerSession[] = [];
  processes: EnforcerProcess[] = [];
  selectedSession: EnforcerSession | null = null;
  liveEvents: { name: string; data: any; time: Date }[] = [];
  violations: EnforcerEvent[] = [];

  // Create session form
  showCreateForm = false;
  newAgent = 'claude';
  newRules = '';
  newMaxCorrections = 2;
  newWorkingDirectory = '';

  // State
  loading = false;
  errorMessage = '';

  private subs: Subscription[] = [];

  displayedColumns = ['status', 'agent', 'score', 'violations', 'turns', 'enabled', 'actions'];
  processColumns = ['kind', 'process', 'agent', 'watcher', 'owner', 'started'];

  constructor(private enforcerService: EnforcerService) {}

  ngOnInit(): void {
    this.refreshSessions();

    this.subs.push(
      this.enforcerService.sessions$.subscribe(s => this.sessions = s),
      this.enforcerService.processes$.subscribe(p => this.processes = p),
      this.enforcerService.activeSession$.subscribe(s => {
        if (s) {
          this.selectedSession = s;
        }
      }),
      this.enforcerService.liveEvents$.subscribe(event => {
        this.liveEvents.unshift({ name: event.name, data: event.data, time: new Date() });
        if (this.liveEvents.length > 100) {
          this.liveEvents = this.liveEvents.slice(0, 100);
        }
      }),
      this.enforcerService.error$.subscribe(err => this.errorMessage = err)
    );
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
    this.enforcerService.disconnectEvents();
  }

  refreshSessions(): void {
    this.loading = true;
    this.enforcerService.refreshSessions();
    this.enforcerService.refreshProcesses();
    this.enforcerService.listSessions().subscribe({
      next: () => this.loading = false,
      error: () => this.loading = false
    });
  }

  selectSession(session: EnforcerSession): void {
    this.selectedSession = session;
    this.enforcerService.connectEvents(session.sessionId);
    this.enforcerService.getSession(session.sessionId).subscribe(s => {
      this.selectedSession = s;
      this.violations = s.events?.filter(e =>
        e.type === 'TEXT_VIOLATION' || e.type === 'TOOL_VIOLATION' || e.type === 'BLOCKED'
      ) || [];
    });
  }

  deselectSession(): void {
    this.selectedSession = null;
    this.liveEvents = [];
    this.violations = [];
    this.enforcerService.disconnectEvents();
  }

  toggleEnforcement(session: EnforcerSession): void {
    const op = session.enabled
      ? this.enforcerService.disableEnforcement(session.sessionId)
      : this.enforcerService.enableEnforcement(session.sessionId);
    op.subscribe(() => this.refreshSessions());
  }

  restartSession(session: EnforcerSession): void {
    this.enforcerService.restartSession(session.sessionId).subscribe({
      next: newSession => {
        this.refreshSessions();
        this.selectSession(newSession);
      },
      error: err => this.errorMessage = err.message || 'Restart failed'
    });
  }

  deleteSession(session: EnforcerSession): void {
    this.enforcerService.deleteSession(session.sessionId).subscribe(() => {
      if (this.selectedSession?.sessionId === session.sessionId) {
        this.deselectSession();
      }
      this.refreshSessions();
    });
  }

  createSession(): void {
    if (!this.newAgent || !this.newRules) {
      this.errorMessage = 'Agent and rules are required';
      return;
    }
    const request: CreateSessionRequest = {
      agentName: this.newAgent,
      rules: this.newRules,
      maxCorrections: this.newMaxCorrections,
      workingDirectory: this.newWorkingDirectory || undefined
    };
    this.enforcerService.createSession(request).subscribe({
      next: session => {
        this.showCreateForm = false;
        this.newRules = '';
        this.refreshSessions();
        this.selectSession(session);
      },
      error: err => this.errorMessage = err.error?.message || 'Create failed'
    });
  }

  getScoreColor(score: number): string {
    if (score >= 0.8) return 'primary';
    if (score >= 0.5) return 'accent';
    return 'warn';
  }

  getScorePercent(score: number): number {
    return Math.round(score * 100);
  }

  getSeverityIcon(severity: string): string {
    switch (severity) {
      case 'critical': return 'error';
      case 'error': return 'warning';
      case 'warning': return 'info';
      default: return 'check_circle';
    }
  }

  getEventTypeLabel(type: string): string {
    switch (type) {
      case 'TEXT_VIOLATION': return 'Text Violation';
      case 'TOOL_VIOLATION': return 'Tool Violation';
      case 'BLOCKED': return 'Blocked';
      case 'REPROMPT': return 'Reprompt';
      case 'SCORE_UPDATE': return 'Score Update';
      case 'TURN_SCORED': return 'Turn Scored';
      default: return type;
    }
  }

  getProcessKindIcon(kind: string): string {
    switch (kind) {
      case 'judge': return 'gavel';
      case 'enforcer': return 'policy';
      default: return 'settings_applications';
    }
  }

  formatPid(pid: number): string {
    return pid && pid > 0 ? String(pid) : '-';
  }

  formatTimestamp(ts: string): string {
    try {
      const d = new Date(ts);
      return d.toLocaleTimeString();
    } catch {
      return ts;
    }
  }

  isSelectedSession(row: EnforcerSession): boolean {
    return this.selectedSession !== null && this.selectedSession.sessionId === row.sessionId;
  }

  trackByIndex(index: number): number {
    return index;
  }
}
