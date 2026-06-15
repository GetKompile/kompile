/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Integration-style tests for JobHistoryComponent.
 *
 * Strategy:
 *   - Use a structural template that replicates the key DOM elements from
 *     job-history.component.html (table rows, status badges, progress bars,
 *     stat cards, loading/error banners, view-mode tabs).
 *   - NO_ERRORS_SCHEMA suppresses unknown-element errors for sub-components
 *     (app-job-log-viewer etc.).
 *   - CommonModule + FormsModule cover *ngFor, *ngIf, [(ngModel)], [ngClass].
 *   - All assertions use fixture.debugElement.query(By.css(...)) and
 *     nativeElement.textContent.
 *   - OnPush CD: call fixture.detectChanges() after calling
 *     cdr.markForCheck() or after changing public state that triggers it.
 */

import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
  discardPeriodicTasks
} from '@angular/core/testing';
import {
  NO_ERRORS_SCHEMA,
  ChangeDetectorRef,
  ChangeDetectionStrategy
} from '@angular/core';
import { By } from '@angular/platform-browser';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';

import { JobHistoryComponent } from './job-history.component';
import {
  JobHistoryService,
  IndexingJobHistory,
  JobStatistics,
  JobStatus,
  IngestPhase,
  SubprocessStatistics
} from '../../services/job-history.service';
import { WebSocketService, WebSocketConnectionState } from '../../services/websocket.service';
import { IngestStatus } from '../../models/api-models';

// ─────────────────────────────────────────────────────────────────────────────
// Minimal structural template matching job-history.component.html key elements
// ─────────────────────────────────────────────────────────────────────────────

const INTEGRATION_TEMPLATE = `
<div class="job-history-container">

  <!-- Error Banner -->
  <div *ngIf="error" class="error-banner">
    <span class="error-text">{{ error }}</span>
    <button class="btn-close" (click)="error = null">&times;</button>
  </div>

  <!-- View Mode Tabs -->
  <div class="view-tabs">
    <button class="tab" [class.active]="viewMode === 'list'" (click)="showList()">Jobs</button>
    <button class="tab" [class.active]="viewMode === 'stats'" (click)="showStats()">Statistics</button>
  </div>

  <!-- Stats Summary Cards (visible in list view when statistics exist) -->
  <div class="stats-summary" *ngIf="viewMode === 'list' && statistics">
    <div class="stat-card">
      <span class="stat-value total-value">{{ statistics.totalJobs }}</span>
      <span class="stat-label">Total Jobs</span>
    </div>
    <div class="stat-card success">
      <span class="stat-value completed-value">{{ statistics.completedJobs }}</span>
      <span class="stat-label">Completed</span>
    </div>
    <div class="stat-card error">
      <span class="stat-value failed-value">{{ statistics.failedJobs }}</span>
      <span class="stat-label">Failed</span>
    </div>
    <div class="stat-card info">
      <span class="stat-value active-value">{{ statistics.activeJobs }}</span>
      <span class="stat-label">Active</span>
    </div>
  </div>

  <!-- List View -->
  <div class="list-view" *ngIf="viewMode === 'list'">

    <!-- Status filter -->
    <div class="filters">
      <div class="filter-group">
        <label>Status:</label>
        <select [(ngModel)]="statusFilter" [ngModelOptions]="{standalone: true}" (change)="onStatusFilterChange()">
          <option *ngFor="let s of statusOptions" [value]="s">{{ s }}</option>
        </select>
      </div>
    </div>

    <!-- Loading indicator -->
    <div *ngIf="loading" class="loading">
      <span class="loading-text">Loading jobs...</span>
    </div>

    <!-- Jobs table -->
    <div class="jobs-table" *ngIf="!loading">
      <table>
        <thead>
          <tr>
            <th>Status</th>
            <th>Type</th>
            <th>File</th>
            <th>Progress</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let job of paginatedJobs; trackBy: trackByTaskId" class="job-row">
            <td>
              <span class="status-badge" [ngClass]="getStatusClass(job.status)">
                {{ job.status }}
              </span>
            </td>
            <td class="type-cell">{{ getJobTypeDisplay(job.contentType) }}</td>
            <td class="file-name">{{ job.fileName }}</td>
            <td>
              <div class="progress-bar-container" *ngIf="job.progressPercent !== undefined">
                <div class="progress-bar" [style.width.%]="job.progressPercent"></div>
                <span class="progress-text">{{ job.progressPercent }}%</span>
              </div>
            </td>
          </tr>
          <tr *ngIf="paginatedJobs.length === 0">
            <td colspan="4" class="no-data">No jobs found</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <!-- Statistics View -->
  <div class="stats-view" *ngIf="viewMode === 'stats' && statistics">
    <div class="stats-card overview-card">
      <h3>Overview</h3>
      <div class="stat-row">
        <span class="label">Total Jobs:</span>
        <span class="value stats-total-value">{{ statistics.totalJobs }}</span>
      </div>
      <div class="stat-row">
        <span class="label">Completed:</span>
        <span class="value stats-completed-value">{{ statistics.completedJobs }}</span>
      </div>
      <div class="stat-row">
        <span class="label">Failed:</span>
        <span class="value stats-failed-value">{{ statistics.failedJobs }}</span>
      </div>
    </div>
  </div>
</div>
`;

// ─────────────────────────────────────────────────────────────────────────────
// Helper factories
// ─────────────────────────────────────────────────────────────────────────────

function makeJob(overrides: Partial<IndexingJobHistory> = {}): IndexingJobHistory {
  return {
    id: 1,
    taskId: `task-${Math.random().toString(36).substr(2, 6)}`,
    fileName: 'test-file.pdf',
    status: 'COMPLETED' as JobStatus,
    lastPhase: 'COMPLETED' as IngestPhase,
    progressPercent: 100,
    startTime: new Date().toISOString(),
    contentType: 'ingest',
    ...overrides
  } as IndexingJobHistory;
}

function makeStatistics(overrides: Partial<JobStatistics> = {}): JobStatistics {
  return {
    totalJobs: 10,
    completedJobs: 7,
    failedJobs: 2,
    cancelledJobs: 0,
    memoryKilledJobs: 0,
    activeJobs: 1,
    statusBreakdown: [],
    failureBreakdown: [],
    embeddingModels: [],
    loaders: [],
    chunkers: [],
    ...overrides
  } as JobStatistics;
}

// ─────────────────────────────────────────────────────────────────────────────
// Spec suite
// ─────────────────────────────────────────────────────────────────────────────

describe('JobHistoryComponent – integration DOM tests', () => {
  let component: JobHistoryComponent;
  let fixture: ComponentFixture<JobHistoryComponent>;

  let jobHistorySpy: jasmine.SpyObj<JobHistoryService>;
  let wsSpy: jasmine.SpyObj<WebSocketService>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  let wsConnectionState$: BehaviorSubject<WebSocketConnectionState>;

  beforeEach(async () => {
    wsConnectionState$ = new BehaviorSubject<WebSocketConnectionState>(
      WebSocketConnectionState.DISCONNECTED
    );

    jobHistorySpy = jasmine.createSpyObj('JobHistoryService', [
      'getRecentJobs',
      'getJobsByStatus',
      'getStatistics',
      'getTaskEvents',
      'getJobSummary',
      'deleteJob',
      'forceCleanup',
      'getRecentSubprocessEvents',
      'getSubprocessStatistics',
      'getSubprocessEventById',
      'formatDuration',
      'formatBytes',
      'formatTimestamp',
      'getStatusClass',
      'getStatusIcon',
      'getPhaseIcon',
      'getSubprocessEventIcon',
      'getSubprocessEventClass',
      'formatSubprocessEventType'
    ]);

    // Default returns
    jobHistorySpy.getRecentJobs.and.returnValue(of([]));
    jobHistorySpy.getStatistics.and.returnValue(of(makeStatistics()));
    jobHistorySpy.getRecentSubprocessEvents.and.returnValue(of([]));
    jobHistorySpy.getSubprocessStatistics.and.returnValue(of({
      available: false,
      totalRestartAttempts: 0,
      totalCrashes: 0,
      restartSuccessRate: 0,
      modelsLoaded: 0
    } as any));
    jobHistorySpy.formatDuration.and.callFake((ms: number | undefined) => ms ? `${ms}ms` : '-');
    jobHistorySpy.formatBytes.and.callFake((b: number | undefined) => b ? `${b}B` : '-');
    jobHistorySpy.formatTimestamp.and.returnValue('2025-01-01 00:00');
    jobHistorySpy.getStatusClass.and.callFake((s: JobStatus) => `status-${s}`);
    jobHistorySpy.getStatusIcon.and.returnValue('check_circle');
    jobHistorySpy.getPhaseIcon.and.returnValue('help');
    jobHistorySpy.getSubprocessEventIcon.and.returnValue('autorenew');
    jobHistorySpy.getSubprocessEventClass.and.returnValue('subprocess-class');
    jobHistorySpy.formatSubprocessEventType.and.returnValue('Subprocess Event');

    wsSpy = jasmine.createSpyObj('WebSocketService', [
      'connect',
      'subscribeToAllTasks',
      'subscribeToVectorPopulation'
    ]);
    // connectionState$ must be a real observable for initWebSocket()
    (wsSpy as any).connectionState$ = wsConnectionState$.asObservable();
    wsSpy.subscribeToAllTasks.and.returnValue(of());
    wsSpy.subscribeToVectorPopulation.and.returnValue(of());

    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);

    await TestBed.configureTestingModule({
      imports: [
        NoopAnimationsModule,
        CommonModule,
        FormsModule
      ],
      declarations: [JobHistoryComponent],
      providers: [
        { provide: JobHistoryService, useValue: jobHistorySpy },
        { provide: WebSocketService, useValue: wsSpy },
        { provide: MatDialog, useValue: dialogSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideComponent(JobHistoryComponent, {
      set: {
        // Override change detection to Default so detectChanges() always works
        changeDetection: ChangeDetectionStrategy.Default,
        template: INTEGRATION_TEMPLATE
      }
    })
    .compileComponents();

    fixture = TestBed.createComponent(JobHistoryComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    component.ngOnDestroy();
  });

  // ─── Flow 1: Jobs list renders with correct status badges ───────────────────

  describe('Flow 1 – jobs list renders rows with status badges', () => {
    it('should render 3 job rows when 3 jobs are returned', fakeAsync(() => {
      const jobs = [
        makeJob({ taskId: 't1', status: 'COMPLETED' }),
        makeJob({ taskId: 't2', status: 'RUNNING', progressPercent: 50 }),
        makeJob({ taskId: 't3', status: 'FAILED' })
      ];
      jobHistorySpy.getRecentJobs.and.returnValue(of(jobs));

      fixture.detectChanges(); // ngOnInit
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const rows = fixture.debugElement.queryAll(By.css('tr.job-row'));
      expect(rows.length).toBe(3);
    }));

    it('should render COMPLETED status badge with correct text', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([
        makeJob({ taskId: 't1', status: 'COMPLETED' })
      ]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const badge = fixture.debugElement.query(By.css('.status-badge'));
      expect(badge.nativeElement.textContent.trim()).toBe('COMPLETED');
    }));

    it('should apply getStatusClass CSS class to COMPLETED badge', fakeAsync(() => {
      jobHistorySpy.getStatusClass.and.returnValue('status-COMPLETED');
      jobHistorySpy.getRecentJobs.and.returnValue(of([
        makeJob({ status: 'COMPLETED' })
      ]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const badge = fixture.debugElement.query(By.css('.status-badge'));
      expect(badge.nativeElement.classList).toContain('status-COMPLETED');
    }));

    it('should apply getStatusClass CSS class to FAILED badge', fakeAsync(() => {
      jobHistorySpy.getStatusClass.and.callFake((s: JobStatus) => `status-${s}`);
      jobHistorySpy.getRecentJobs.and.returnValue(of([
        makeJob({ status: 'FAILED' })
      ]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const badge = fixture.debugElement.query(By.css('.status-badge'));
      expect(badge.nativeElement.classList).toContain('status-FAILED');
    }));

    it('should render progress bar with 50% width for RUNNING job', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([
        makeJob({ status: 'RUNNING', progressPercent: 50 })
      ]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const bar = fixture.debugElement.query(By.css('.progress-bar'));
      expect(bar).not.toBeNull();
      // style.width is applied via [style.width.%]="50"
      expect(bar.nativeElement.style.width).toBe('50%');
    }));

    it('should display file names in the file-name cells', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([
        makeJob({ fileName: 'report.pdf', status: 'COMPLETED' })
      ]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const fileCell = fixture.debugElement.query(By.css('td.file-name'));
      expect(fileCell.nativeElement.textContent.trim()).toBe('report.pdf');
    }));
  });

  // ─── Flow 2: Statistics summary cards show correct counts ───────────────────

  describe('Flow 2 – statistics summary cards', () => {
    it('should show stat-card elements when statistics are loaded', fakeAsync(() => {
      jobHistorySpy.getStatistics.and.returnValue(of(makeStatistics({
        totalJobs: 10, completedJobs: 7, failedJobs: 2, activeJobs: 1
      })));
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const statCards = fixture.debugElement.queryAll(By.css('.stat-card'));
      expect(statCards.length).toBeGreaterThanOrEqual(4);
    }));

    it('should display totalJobs count in total-value span', fakeAsync(() => {
      jobHistorySpy.getStatistics.and.returnValue(of(makeStatistics({ totalJobs: 42 })));
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const totalEl = fixture.debugElement.query(By.css('.total-value'));
      expect(totalEl.nativeElement.textContent.trim()).toBe('42');
    }));

    it('should display completedJobs in completed-value span', fakeAsync(() => {
      jobHistorySpy.getStatistics.and.returnValue(of(makeStatistics({ completedJobs: 7 })));
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const completedEl = fixture.debugElement.query(By.css('.completed-value'));
      expect(completedEl.nativeElement.textContent.trim()).toBe('7');
    }));

    it('should display failedJobs in failed-value span', fakeAsync(() => {
      jobHistorySpy.getStatistics.and.returnValue(of(makeStatistics({ failedJobs: 3 })));
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const failedEl = fixture.debugElement.query(By.css('.failed-value'));
      expect(failedEl.nativeElement.textContent.trim()).toBe('3');
    }));

    it('should display activeJobs in active-value span', fakeAsync(() => {
      jobHistorySpy.getStatistics.and.returnValue(of(makeStatistics({ activeJobs: 5 })));
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const activeEl = fixture.debugElement.query(By.css('.active-value'));
      expect(activeEl.nativeElement.textContent.trim()).toBe('5');
    }));
  });

  // ─── Flow 3: Filter by status calls service with correct params ─────────────

  describe('Flow 3 – status filter triggers service call', () => {
    it('should call getJobsByStatus when statusFilter is not ALL', fakeAsync(() => {
      jobHistorySpy.getJobsByStatus = jasmine.createSpy('getJobsByStatus').and.returnValue(of([]));
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      component.statusFilter = 'FAILED';
      component.onStatusFilterChange();
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();

      expect(jobHistorySpy.getJobsByStatus).toHaveBeenCalledWith('FAILED', jasmine.any(Number));
    }));

    it('should call getRecentJobs when statusFilter is ALL', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      component.statusFilter = 'ALL';
      component.onStatusFilterChange();
      tick(500);
      discardPeriodicTasks();

      expect(jobHistorySpy.getRecentJobs).toHaveBeenCalled();
    }));

    it('should reset currentPage to 0 on status filter change', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();

      component.currentPage = 3;
      component.onStatusFilterChange();

      expect(component.currentPage).toBe(0);
    }));
  });

  // ─── Flow 4: Loading state shows spinner, then hides ────────────────────────

  describe('Flow 4 – loading state', () => {
    it('should show loading div when loading is true', fakeAsync(() => {
      // Let ngOnInit complete first
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      // Now set loading state manually
      component.loading = true;
      fixture.detectChanges();

      const loadingEl = fixture.debugElement.query(By.css('.loading'));
      expect(loadingEl).not.toBeNull();
      expect(loadingEl.nativeElement.textContent).toContain('Loading jobs...');
    }));

    it('should hide loading div when loading is false', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const loadingEl = fixture.debugElement.query(By.css('.loading'));
      expect(loadingEl).toBeNull();
    }));

    it('should show jobs-table when loading finishes', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const table = fixture.debugElement.query(By.css('.jobs-table'));
      expect(table).not.toBeNull();
    }));
  });

  // ─── Flow 5: Error banner shows and dismisses ───────────────────────────────

  describe('Flow 5 – error banner', () => {
    it('should show error-banner when error is set', fakeAsync(() => {
      // Let ngOnInit complete first
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      // Now set error state manually
      component.error = 'Something went wrong';
      fixture.detectChanges();

      const banner = fixture.debugElement.query(By.css('.error-banner'));
      expect(banner).not.toBeNull();
      expect(banner.nativeElement.textContent).toContain('Something went wrong');
    }));

    it('should not show error-banner when error is null', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const banner = fixture.debugElement.query(By.css('.error-banner'));
      expect(banner).toBeNull();
    }));

    it('should dismiss error banner when close button is clicked', fakeAsync(() => {
      // Let ngOnInit complete first
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      component.error = 'Some error message';
      fixture.detectChanges();

      const closeBtn = fixture.debugElement.query(By.css('.btn-close'));
      expect(closeBtn).not.toBeNull();
      closeBtn.nativeElement.click();
      fixture.detectChanges();

      const banner = fixture.debugElement.query(By.css('.error-banner'));
      expect(banner).toBeNull();
      expect(component.error).toBeNull();
    }));

    it('should show error text in error-text span', fakeAsync(() => {
      // Let ngOnInit complete first
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      component.error = 'Detailed error info';
      fixture.detectChanges();

      const errorText = fixture.debugElement.query(By.css('.error-text'));
      expect(errorText.nativeElement.textContent.trim()).toBe('Detailed error info');
    }));
  });

  // ─── Flow 6: Empty state shows "No jobs found" ──────────────────────────────

  describe('Flow 6 – empty state', () => {
    it('should show "No jobs found" when jobs list is empty', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const noData = fixture.debugElement.query(By.css('.no-data'));
      expect(noData).not.toBeNull();
      expect(noData.nativeElement.textContent.trim()).toBe('No jobs found');
    }));

    it('should not show "No jobs found" when jobs exist', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([makeJob()]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const noData = fixture.debugElement.query(By.css('.no-data'));
      expect(noData).toBeNull();
    }));
  });

  // ─── Flow 7: View mode tabs switch between list and stats ───────────────────

  describe('Flow 7 – view mode tabs', () => {
    it('should render Jobs tab as active in default list mode', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const tabs = fixture.debugElement.queryAll(By.css('.tab'));
      const jobsTab = tabs[0];
      expect(jobsTab.nativeElement.classList).toContain('active');
    }));

    it('should set viewMode to stats when showStats() is called', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      component.showStats();
      fixture.detectChanges();

      expect(component.viewMode).toBe('stats');
    }));

    it('should render stats-view when viewMode is stats and statistics is loaded', fakeAsync(() => {
      jobHistorySpy.getStatistics.and.returnValue(of(makeStatistics({ totalJobs: 15 })));
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      component.showStats();
      fixture.detectChanges();

      const statsView = fixture.debugElement.query(By.css('.stats-view'));
      expect(statsView).not.toBeNull();
    }));

    it('should render correct total in stats-view overview card', fakeAsync(() => {
      jobHistorySpy.getStatistics.and.returnValue(of(makeStatistics({ totalJobs: 99 })));
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      component.showStats();
      fixture.detectChanges();

      const totalValue = fixture.debugElement.query(By.css('.stats-total-value'));
      expect(totalValue.nativeElement.textContent.trim()).toBe('99');
    }));

    it('should hide stats-view when returning to list', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      component.showStats();
      fixture.detectChanges();

      component.showList();
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const statsView = fixture.debugElement.query(By.css('.stats-view'));
      expect(statsView).toBeNull();
    }));

    it('should show list-view when showList() is called', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      component.showStats();
      fixture.detectChanges();

      component.showList();
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const listView = fixture.debugElement.query(By.css('.list-view'));
      expect(listView).not.toBeNull();
    }));

    it('clicking Stats tab button calls showStats()', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      spyOn(component, 'showStats').and.callThrough();
      const tabs = fixture.debugElement.queryAll(By.css('.tab'));
      tabs[1].nativeElement.click();

      expect(component.showStats).toHaveBeenCalled();
    }));

    it('clicking Jobs tab button calls showList()', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      component.showStats();
      fixture.detectChanges();

      spyOn(component, 'showList').and.callThrough();
      const tabs = fixture.debugElement.queryAll(By.css('.tab'));
      tabs[0].nativeElement.click();

      expect(component.showList).toHaveBeenCalled();
    }));
  });

  // ─── Additional edge cases ───────────────────────────────────────────────────

  describe('Additional edge cases', () => {
    it('should render stats-summary only in list mode (not stats mode)', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));
      jobHistorySpy.getStatistics.and.returnValue(of(makeStatistics()));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('.stats-summary'))).not.toBeNull();

      component.showStats();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('.stats-summary'))).toBeNull();
    }));

    it('should show progress-text alongside progress-bar', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([
        makeJob({ status: 'RUNNING', progressPercent: 75 })
      ]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const progressText = fixture.debugElement.query(By.css('.progress-text'));
      expect(progressText).not.toBeNull();
      expect(progressText.nativeElement.textContent).toContain('75%');
    }));

    it('should display Document Ingest for ingest contentType', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([
        makeJob({ contentType: 'ingest' })
      ]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const typeCell = fixture.debugElement.query(By.css('.type-cell'));
      expect(typeCell.nativeElement.textContent.trim()).toBe('Document Ingest');
    }));

    it('should display Vector Population for vector-population contentType', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([
        makeJob({ contentType: 'vector-population' })
      ]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const typeCell = fixture.debugElement.query(By.css('.type-cell'));
      expect(typeCell.nativeElement.textContent.trim()).toBe('Vector Population');
    }));

    it('should not show progress-bar-container when progressPercent is undefined', fakeAsync(() => {
      const job = makeJob({ status: 'COMPLETED' });
      delete job.progressPercent;
      jobHistorySpy.getRecentJobs.and.returnValue(of([job]));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      const progressContainer = fixture.debugElement.query(By.css('.progress-bar-container'));
      expect(progressContainer).toBeNull();
    }));

    it('component should create without errors', fakeAsync(() => {
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      expect(component).toBeTruthy();
    }));

    it('statusOptions should contain ALL, RUNNING, COMPLETED, FAILED', () => {
      expect(component.statusOptions).toContain('ALL');
      expect(component.statusOptions).toContain('RUNNING');
      expect(component.statusOptions).toContain('COMPLETED');
      expect(component.statusOptions).toContain('FAILED');
    });

    it('paginatedJobs should be empty slice when jobs is empty', fakeAsync(() => {
      jobHistorySpy.getRecentJobs.and.returnValue(of([]));
      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      expect(component.paginatedJobs.length).toBe(0);
    }));

    it('paginatedJobs should return first pageSize jobs', fakeAsync(() => {
      const manyJobs = Array.from({ length: 25 }, (_, i) => makeJob({ taskId: `t${i}` }));
      jobHistorySpy.getRecentJobs.and.returnValue(of(manyJobs));

      fixture.detectChanges();
      tick(500);
      discardPeriodicTasks();
      fixture.detectChanges();

      expect(component.paginatedJobs.length).toBe(component.pageSize);
    }));
  });
});
