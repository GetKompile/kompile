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

import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
  discardPeriodicTasks
} from '@angular/core/testing';
import { NO_ERRORS_SCHEMA, ChangeDetectorRef } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { BehaviorSubject, of, throwError, Subject } from 'rxjs';

import { JobHistoryComponent } from './job-history.component';
import {
  JobHistoryService,
  IndexingJobHistory,
  JobStatistics,
  JobSummary,
  IngestEvent,
  SubprocessEvent,
  SubprocessStatistics,
  JobStatus,
  IngestPhase,
  FailureReason
} from '../../services/job-history.service';
import { WebSocketService, WebSocketConnectionState } from '../../services/websocket.service';
import { IngestStatus } from '../../models/api-models';

// ===================== HELPER FACTORIES =====================

function makeJob(overrides: Partial<IndexingJobHistory> = {}): IndexingJobHistory {
  return {
    id: 1,
    taskId: 'task-001',
    fileName: 'document.pdf',
    status: 'COMPLETED',
    startTime: new Date().toISOString(),
    contentType: 'application/pdf',
    progressPercent: 100,
    lastPhase: 'COMPLETED',
    ...overrides
  };
}

function makeStats(overrides: Partial<JobStatistics> = {}): JobStatistics {
  return {
    totalJobs: 10,
    completedJobs: 8,
    failedJobs: 1,
    cancelledJobs: 0,
    memoryKilledJobs: 0,
    activeJobs: 1,
    statusBreakdown: [],
    failureBreakdown: [],
    embeddingModels: ['bge-base'],
    loaders: ['pdf'],
    chunkers: ['recursive'],
    ...overrides
  };
}

function makeJobSummary(overrides: Partial<JobSummary> = {}): JobSummary {
  return {
    taskId: 'task-001',
    fileName: 'document.pdf',
    status: 'COMPLETED',
    progressPercent: 100,
    startTime: new Date().toISOString(),
    parameters: { loaderUsed: 'pdf', embeddingModelUsed: 'bge-base' },
    results: { documentsLoaded: 5, chunksCreated: 20, chunksEmbedded: 20, documentsIndexed: 5 },
    environment: { javaVersion: '17', nd4jBackend: 'cpu' },
    memoryPercent: { usageAtStart: 40, usageAtEnd: 55, peakUsage: 60 },
    ...overrides
  };
}

function makeSubprocessEvent(overrides: Partial<SubprocessEvent> = {}): SubprocessEvent {
  return {
    id: 99,
    eventType: 'SUBPROCESS_STARTED',
    modelId: 'bge-base',
    timestamp: new Date().toISOString(),
    taskId: 'subprocess-99',
    ...overrides
  };
}

function makeSubprocessStats(overrides: Partial<SubprocessStatistics> = {}): SubprocessStatistics {
  return {
    available: true,
    totalCrashes: 2,
    totalRestartAttempts: 4,
    successfulRestarts: 3,
    exhaustedRestarts: 1,
    modelsLoaded: 2,
    modelsFailed: 1,
    restartSuccessRate: 75,
    ...overrides
  };
}

function makeIngestEvent(overrides: Partial<IngestEvent> = {}): IngestEvent {
  return {
    taskId: 'task-001',
    timestamp: new Date().toISOString(),
    eventType: 'STARTED',
    message: 'Job started',
    ...overrides
  };
}

// ===================== SPEC =====================

describe('JobHistoryComponent', () => {
  let component: JobHistoryComponent;
  let fixture: ComponentFixture<JobHistoryComponent>;

  let jobHistorySpy: jasmine.SpyObj<JobHistoryService>;
  let wsSpy: jasmine.SpyObj<WebSocketService>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;
  let cdrSpy: jasmine.SpyObj<ChangeDetectorRef>;

  // BehaviorSubject used for connectionState$
  let connectionState$: BehaviorSubject<WebSocketConnectionState>;
  // Subjects used for subscribeToAllTasks / subscribeToVectorPopulation
  let allTasksSubject: Subject<any>;
  let vectorPopSubject: Subject<any>;

  beforeEach(async () => {
    connectionState$ = new BehaviorSubject<WebSocketConnectionState>(
      WebSocketConnectionState.DISCONNECTED
    );
    allTasksSubject = new Subject<any>();
    vectorPopSubject = new Subject<any>();

    jobHistorySpy = jasmine.createSpyObj<JobHistoryService>('JobHistoryService', [
      'getRecentJobs',
      'getJobsByStatus',
      'getStatistics',
      'getRecentSubprocessEvents',
      'getSubprocessStatistics',
      'getTaskEvents',
      'getJobSummary',
      'getSubprocessEventById',
      'deleteJob',
      'forceCleanup',
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

    // Default return values
    jobHistorySpy.getRecentJobs.and.returnValue(of([]));
    jobHistorySpy.getJobsByStatus.and.returnValue(of([]));
    jobHistorySpy.getStatistics.and.returnValue(of(makeStats()));
    jobHistorySpy.getRecentSubprocessEvents.and.returnValue(of([]));
    jobHistorySpy.getSubprocessStatistics.and.returnValue(of(makeSubprocessStats()));
    jobHistorySpy.getTaskEvents.and.returnValue(of({ taskId: 'task-001', eventCount: 0, events: [] }));
    jobHistorySpy.getJobSummary.and.returnValue(of(makeJobSummary()));
    jobHistorySpy.getSubprocessEventById.and.returnValue(of(makeSubprocessEvent()));
    jobHistorySpy.deleteJob.and.returnValue(of({ deleted: true, taskId: 'task-001' }));
    jobHistorySpy.forceCleanup.and.returnValue(of({ deleted: 5, olderThanDays: 30 }));
    jobHistorySpy.formatDuration.and.callFake((ms?: number) => ms !== undefined ? `${ms}ms` : '-');
    jobHistorySpy.formatBytes.and.callFake((b?: number) => b !== undefined ? `${b}B` : '-');
    jobHistorySpy.formatTimestamp.and.callFake((t: any) => t ? String(t) : '-');
    jobHistorySpy.getStatusClass.and.callFake((s: JobStatus) => `status-${s.toLowerCase()}`);
    jobHistorySpy.getStatusIcon.and.callFake((s: JobStatus) => 'icon');
    jobHistorySpy.getPhaseIcon.and.callFake((p?: IngestPhase) => 'phase-icon');
    jobHistorySpy.getSubprocessEventIcon.and.returnValue('sp-icon');
    jobHistorySpy.getSubprocessEventClass.and.returnValue('sp-class');
    jobHistorySpy.formatSubprocessEventType.and.callFake((t: any) => String(t));

    wsSpy = jasmine.createSpyObj<WebSocketService>('WebSocketService', [
      'connect',
      'disconnect',
      'subscribeToAllTasks',
      'subscribeToVectorPopulation'
    ]);
    Object.defineProperty(wsSpy, 'connectionState$', { get: () => connectionState$.asObservable() });
    wsSpy.subscribeToAllTasks.and.returnValue(allTasksSubject.asObservable());
    wsSpy.subscribeToVectorPopulation.and.returnValue(vectorPopSubject.asObservable());

    dialogSpy = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    // Default: dialog cancelled (returns false)
    dialogSpy.open.and.returnValue({
      afterClosed: () => of(false)
    } as MatDialogRef<any>);

    cdrSpy = jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['markForCheck', 'detectChanges']);

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule],
      declarations: [JobHistoryComponent],
      providers: [
        { provide: JobHistoryService, useValue: jobHistorySpy },
        { provide: WebSocketService, useValue: wsSpy },
        { provide: MatDialog, useValue: dialogSpy },
        { provide: ChangeDetectorRef, useValue: cdrSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
      .overrideTemplate(JobHistoryComponent, '<div></div>')
      .compileComponents();

    fixture = TestBed.createComponent(JobHistoryComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    component.ngOnDestroy();
  });

  // ===================== CREATION =====================

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should have default UI state values', () => {
    expect(component.loading).toBeFalse();
    expect(component.error).toBeNull();
    expect(component.viewMode).toBe('list');
    expect(component.autoRefresh).toBeTrue();
    expect(component.refreshIntervalSeconds).toBe(30);
  });

  it('should have default filter values', () => {
    expect(component.lookbackHours).toBe(24);
    expect(component.statusFilter).toBe('ALL');
    expect(component.jobTypeFilter).toBe('ALL');
    expect(component.searchTerm).toBe('');
  });

  it('should have default data values', () => {
    expect(component.jobs).toEqual([]);
    expect(component.statistics).toBeNull();
    expect(component.selectedJob).toBeNull();
    expect(component.events).toEqual([]);
    expect(component.subprocessEvents).toEqual([]);
    expect(component.subprocessStatistics).toBeNull();
  });

  // ===================== ngOnInit =====================

  it('should call loadJobs, loadStatistics, loadSubprocessEvents, loadSubprocessStatistics on init', () => {
    fixture.detectChanges(); // triggers ngOnInit
    expect(jobHistorySpy.getRecentJobs).toHaveBeenCalledWith(24);
    expect(jobHistorySpy.getStatistics).toHaveBeenCalledWith(24);
    expect(jobHistorySpy.getRecentSubprocessEvents).toHaveBeenCalledWith(24);
    expect(jobHistorySpy.getSubprocessStatistics).toHaveBeenCalled();
  });

  it('should call webSocketService.connect on init', () => {
    fixture.detectChanges();
    expect(wsSpy.connect).toHaveBeenCalled();
  });

  it('should subscribe to allTasks and vectorPopulation when WS connected', () => {
    fixture.detectChanges();
    // Emit connected state
    connectionState$.next(WebSocketConnectionState.CONNECTED);
    expect(wsSpy.subscribeToAllTasks).toHaveBeenCalled();
    expect(wsSpy.subscribeToVectorPopulation).toHaveBeenCalled();
  });

  it('should set wsConnected=true when WS emits CONNECTED', () => {
    fixture.detectChanges();
    connectionState$.next(WebSocketConnectionState.CONNECTED);
    expect(component.wsConnected).toBeTrue();
  });

  it('should set wsConnected=false when WS emits DISCONNECTED', () => {
    fixture.detectChanges();
    connectionState$.next(WebSocketConnectionState.CONNECTED);
    connectionState$.next(WebSocketConnectionState.DISCONNECTED);
    expect(component.wsConnected).toBeFalse();
  });

  it('should start auto-refresh interval on init when autoRefresh is true', fakeAsync(() => {
    fixture.detectChanges();
    // Advance time by one interval
    tick(30000);
    // Should have called loadJobs again (once from init + once from interval)
    expect(jobHistorySpy.getRecentJobs.calls.count()).toBeGreaterThanOrEqual(2);
    discardPeriodicTasks();
  }));

  // ===================== LOAD JOBS =====================

  it('should load recent jobs when statusFilter is ALL', () => {
    component.statusFilter = 'ALL';
    component.lookbackHours = 24;
    component.loadJobs();
    expect(jobHistorySpy.getRecentJobs).toHaveBeenCalledWith(24);
  });

  it('should load jobs by status when statusFilter is not ALL', () => {
    component.statusFilter = 'FAILED';
    component.loadJobs();
    expect(jobHistorySpy.getJobsByStatus).toHaveBeenCalledWith('FAILED', 24);
  });

  it('should populate jobs array on successful load', () => {
    const jobs = [makeJob({ taskId: 'task-001' }), makeJob({ taskId: 'task-002' })];
    jobHistorySpy.getRecentJobs.and.returnValue(of(jobs));
    component.loadJobs();
    expect(component.jobs.length).toBe(2);
    expect(component.loading).toBeFalse();
    expect(component.error).toBeNull();
  });

  it('should set error message on loadJobs failure', () => {
    jobHistorySpy.getRecentJobs.and.returnValue(throwError(() => new Error('network error')));
    component.loadJobs();
    expect(component.error).toBe('Failed to load job history');
    expect(component.loading).toBeFalse();
  });

  it('should set loading=false and jobs after successful loadJobs (CDR side-effect)', () => {
    // Verify the state changes that result from cdr.markForCheck being called
    jobHistorySpy.getRecentJobs.and.returnValue(of([makeJob()]));
    component.loadJobs();
    // After success the component is in a consistent state
    expect(component.loading).toBeFalse();
    expect(component.jobs.length).toBe(1);
    expect(component.error).toBeNull();
  });

  // ===================== LOAD STATISTICS =====================

  it('should populate statistics on successful load', () => {
    const stats = makeStats({ totalJobs: 42 });
    jobHistorySpy.getStatistics.and.returnValue(of(stats));
    component.loadStatistics();
    expect(component.statistics).toEqual(stats);
    expect(component.statistics!.totalJobs).toBe(42);
  });

  it('should not set error on statistics failure (silent)', () => {
    jobHistorySpy.getStatistics.and.returnValue(throwError(() => new Error('stats error')));
    component.error = null;
    component.loadStatistics();
    expect(component.error).toBeNull();
  });

  // ===================== LOAD SUBPROCESS =====================

  it('should populate subprocessEvents on successful load', () => {
    const events = [makeSubprocessEvent({ id: 1 }), makeSubprocessEvent({ id: 2 })];
    jobHistorySpy.getRecentSubprocessEvents.and.returnValue(of(events));
    component.loadSubprocessEvents();
    expect(component.subprocessEvents.length).toBe(2);
  });

  it('should populate subprocessStatistics on successful load', () => {
    const stats = makeSubprocessStats({ totalCrashes: 5 });
    jobHistorySpy.getSubprocessStatistics.and.returnValue(of(stats));
    component.loadSubprocessStatistics();
    expect(component.subprocessStatistics).toEqual(stats);
  });

  // ===================== LOAD JOB DETAIL =====================

  it('should set selectedJob and viewMode="detail" on successful loadJobDetail', () => {
    const summary = makeJobSummary({ taskId: 'task-abc' });
    jobHistorySpy.getJobSummary.and.returnValue(of(summary));
    component.loadJobDetail('task-abc');
    expect(component.selectedJob).toEqual(summary);
    expect(component.viewMode).toBe('detail');
    expect(component.loading).toBeFalse();
  });

  it('should parse nd4jEnvironmentJson when present in loadJobDetail', () => {
    const nd4jData = { blasMajorOrderThreads: 4, debug: false };
    const summary = makeJobSummary({
      environment: { nd4jEnvironmentJson: JSON.stringify(nd4jData) }
    });
    jobHistorySpy.getJobSummary.and.returnValue(of(summary));
    component.loadJobDetail('task-abc');
    expect(component.parsedNd4jEnvironment).toEqual(nd4jData);
  });

  it('should set parsedNd4jEnvironment to null on invalid JSON', () => {
    const summary = makeJobSummary({
      environment: { nd4jEnvironmentJson: '{invalid json}' }
    });
    jobHistorySpy.getJobSummary.and.returnValue(of(summary));
    component.loadJobDetail('task-abc');
    expect(component.parsedNd4jEnvironment).toBeNull();
  });

  it('should set error on loadJobDetail failure', () => {
    jobHistorySpy.getJobSummary.and.returnValue(throwError(() => new Error('not found')));
    component.loadJobDetail('task-xyz');
    expect(component.error).toContain('task-xyz');
    expect(component.loading).toBeFalse();
  });

  it('should load events after loadJobDetail succeeds', () => {
    const eventsResp = { taskId: 'task-001', eventCount: 1, events: [makeIngestEvent()] };
    jobHistorySpy.getTaskEvents.and.returnValue(of(eventsResp));
    component.loadJobDetail('task-001');
    expect(jobHistorySpy.getTaskEvents).toHaveBeenCalledWith('task-001');
    expect(component.events.length).toBe(1);
  });

  it('should set events to [] on loadEvents failure', () => {
    jobHistorySpy.getTaskEvents.and.returnValue(throwError(() => new Error('err')));
    component.loadEvents('task-bad');
    expect(component.events).toEqual([]);
  });

  // ===================== SUBPROCESS EVENT DETAIL =====================

  it('should set selectedSubprocessEvent on loadSubprocessEventDetail success', () => {
    const event = makeSubprocessEvent({ id: 7 });
    jobHistorySpy.getSubprocessEventById.and.returnValue(of(event));
    component.loadSubprocessEventDetail(7);
    expect(component.selectedSubprocessEvent).toEqual(event);
    expect(component.viewMode).toBe('detail');
  });

  it('should set error on loadSubprocessEventDetail failure', () => {
    jobHistorySpy.getSubprocessEventById.and.returnValue(throwError(() => new Error('not found')));
    component.loadSubprocessEventDetail(99);
    expect(component.error).toContain('99');
  });

  // ===================== FILTER METHODS =====================

  it('should filter jobs by jobTypeFilter=vector-population', () => {
    component.jobTypeFilter = 'vector-population';
    const jobs = [
      makeJob({ contentType: 'vector-population', taskId: 'vp1' }),
      makeJob({ contentType: 'application/pdf', taskId: 'pdf1' }),
    ];
    const result = component.filterJobs(jobs);
    expect(result.length).toBe(1);
    expect(result[0].taskId).toBe('vp1');
  });

  it('should filter jobs by jobTypeFilter=ingest (excludes vector-population and embedding)', () => {
    component.jobTypeFilter = 'ingest';
    const jobs = [
      makeJob({ contentType: 'application/pdf', taskId: 'doc1' }),
      makeJob({ contentType: 'vector-population', taskId: 'vp1' }),
      makeJob({ contentType: 'embedding', taskId: 'emb1' }),
    ];
    const result = component.filterJobs(jobs);
    expect(result.length).toBe(1);
    expect(result[0].taskId).toBe('doc1');
  });

  it('should filter jobs by searchTerm matching fileName', () => {
    component.jobTypeFilter = 'ALL';
    component.searchTerm = 'report';
    const jobs = [
      makeJob({ fileName: 'annual_report.pdf', taskId: 't1' }),
      makeJob({ fileName: 'invoice.pdf', taskId: 't2' }),
    ];
    const result = component.filterJobs(jobs);
    expect(result.length).toBe(1);
    expect(result[0].taskId).toBe('t1');
  });

  it('should filter jobs by searchTerm matching taskId', () => {
    component.jobTypeFilter = 'ALL';
    component.searchTerm = 'abc-123';
    const jobs = [
      makeJob({ taskId: 'abc-123-xyz', fileName: 'file.pdf' }),
      makeJob({ taskId: 'def-456', fileName: 'other.pdf' }),
    ];
    const result = component.filterJobs(jobs);
    expect(result.length).toBe(1);
    expect(result[0].taskId).toBe('abc-123-xyz');
  });

  it('should return all jobs when jobTypeFilter=ALL and no searchTerm', () => {
    component.jobTypeFilter = 'ALL';
    component.searchTerm = '';
    const jobs = [makeJob({ taskId: 't1' }), makeJob({ taskId: 't2' }), makeJob({ taskId: 't3' })];
    expect(component.filterJobs(jobs).length).toBe(3);
  });

  it('onStatusFilterChange should reset currentPage and call loadJobs', () => {
    component.currentPage = 5;
    component.onStatusFilterChange();
    expect(component.currentPage).toBe(0);
    expect(jobHistorySpy.getRecentJobs).toHaveBeenCalled();
  });

  it('onJobTypeFilterChange should reset currentPage and call loadJobs', () => {
    component.currentPage = 3;
    component.onJobTypeFilterChange();
    expect(component.currentPage).toBe(0);
    expect(jobHistorySpy.getRecentJobs).toHaveBeenCalled();
  });

  it('onSearchChange should call loadJobs', () => {
    component.onSearchChange();
    expect(jobHistorySpy.getRecentJobs).toHaveBeenCalled();
  });

  it('onLookbackChange should call loadJobs and loadStatistics', () => {
    component.onLookbackChange();
    expect(jobHistorySpy.getRecentJobs).toHaveBeenCalled();
    expect(jobHistorySpy.getStatistics).toHaveBeenCalled();
  });

  // ===================== VIEW MODE =====================

  it('showList should set viewMode=list and clear selectedJob', () => {
    component.viewMode = 'detail';
    component.selectedJob = makeJobSummary();
    component.showList();
    expect(component.viewMode).toBe('list');
    expect(component.selectedJob).toBeNull();
  });

  it('showStats should set viewMode=stats and reload statistics', () => {
    component.showStats();
    expect(component.viewMode).toBe('stats');
    expect(jobHistorySpy.getStatistics).toHaveBeenCalled();
  });

  it('showDetail should call loadJobDetail for non-subprocess job', () => {
    const job = makeJob({ taskId: 'task-xyz', contentType: 'application/pdf' });
    component.showDetail(job);
    expect(jobHistorySpy.getJobSummary).toHaveBeenCalledWith('task-xyz');
  });

  it('showDetail should call loadSubprocessEventDetail for subprocess job with matching taskId', () => {
    const job = makeJob({ taskId: 'subprocess-42', contentType: 'subprocess', id: 42 });
    component.showDetail(job);
    expect(jobHistorySpy.getSubprocessEventById).toHaveBeenCalledWith(42);
  });

  it('showDetail should call loadSubprocessEventDetail using job.id as fallback', () => {
    // taskId doesn't match 'subprocess-{num}' pattern
    const job = makeJob({ taskId: 'sp-custom', contentType: 'subprocess', id: 55 });
    component.showDetail(job);
    expect(jobHistorySpy.getSubprocessEventById).toHaveBeenCalledWith(55);
  });

  // ===================== AUTO-REFRESH =====================

  it('toggleAutoRefresh should toggle autoRefresh flag', () => {
    component.autoRefresh = true;
    component.toggleAutoRefresh();
    expect(component.autoRefresh).toBeFalse();
  });

  it('toggleAutoRefresh to true should restart auto-refresh', fakeAsync(() => {
    component.autoRefresh = false;
    component.toggleAutoRefresh();
    expect(component.autoRefresh).toBeTrue();
    tick(30000);
    expect(jobHistorySpy.getRecentJobs.calls.count()).toBeGreaterThanOrEqual(1);
    discardPeriodicTasks();
  }));

  it('manualRefresh should call all four loaders', () => {
    component.manualRefresh();
    expect(jobHistorySpy.getRecentJobs).toHaveBeenCalled();
    expect(jobHistorySpy.getStatistics).toHaveBeenCalled();
    expect(jobHistorySpy.getRecentSubprocessEvents).toHaveBeenCalled();
    expect(jobHistorySpy.getSubprocessStatistics).toHaveBeenCalled();
  });

  // ===================== CLEANUP DIALOG =====================

  it('openCleanupDialog should set showCleanupDialog=true', () => {
    component.openCleanupDialog();
    expect(component.showCleanupDialog).toBeTrue();
  });

  it('closeCleanupDialog should set showCleanupDialog=false', () => {
    component.showCleanupDialog = true;
    component.closeCleanupDialog();
    expect(component.showCleanupDialog).toBeFalse();
  });

  it('performCleanup should call forceCleanup and reload on success', () => {
    jobHistorySpy.forceCleanup.and.returnValue(of({ deleted: 3, olderThanDays: 30 }));
    component.showCleanupDialog = true;
    component.performCleanup();
    expect(jobHistorySpy.forceCleanup).toHaveBeenCalledWith(component.cleanupDays);
    expect(component.showCleanupDialog).toBeFalse();
    expect(jobHistorySpy.getRecentJobs).toHaveBeenCalled();
    expect(jobHistorySpy.getStatistics).toHaveBeenCalled();
    expect(component.loading).toBeFalse();
  });

  it('performCleanup should set error on failure', () => {
    jobHistorySpy.forceCleanup.and.returnValue(throwError(() => new Error('cleanup failed')));
    component.performCleanup();
    expect(component.error).toBe('Cleanup failed');
    expect(component.loading).toBeFalse();
  });

  // ===================== DELETE JOB =====================

  it('deleteJob should open confirmation dialog', () => {
    const mockEvent = { stopPropagation: jasmine.createSpy('stopPropagation') } as any as Event;
    component.deleteJob('task-001', mockEvent);
    expect(dialogSpy.open).toHaveBeenCalled();
    expect(mockEvent.stopPropagation).toHaveBeenCalled();
  });

  it('deleteJob should call jobHistoryService.deleteJob when confirmed', () => {
    dialogSpy.open.and.returnValue({
      afterClosed: () => of(true)
    } as MatDialogRef<any>);
    jobHistorySpy.deleteJob.and.returnValue(of({ deleted: true, taskId: 'task-001' }));
    component.jobs = [makeJob({ taskId: 'task-001' }), makeJob({ taskId: 'task-002' })];

    const mockEvent = { stopPropagation: jasmine.createSpy('stopPropagation') } as any as Event;
    component.deleteJob('task-001', mockEvent);

    expect(jobHistorySpy.deleteJob).toHaveBeenCalledWith('task-001');
    expect(component.jobs.length).toBe(1);
    expect(component.jobs[0].taskId).toBe('task-002');
  });

  it('deleteJob should clear selectedJob if it is the deleted job', () => {
    dialogSpy.open.and.returnValue({
      afterClosed: () => of(true)
    } as MatDialogRef<any>);
    jobHistorySpy.deleteJob.and.returnValue(of({ deleted: true, taskId: 'task-001' }));
    component.jobs = [makeJob({ taskId: 'task-001' })];
    component.selectedJob = makeJobSummary({ taskId: 'task-001' });
    component.viewMode = 'detail';

    const mockEvent = { stopPropagation: jasmine.createSpy('stopPropagation') } as any as Event;
    component.deleteJob('task-001', mockEvent);

    expect(component.selectedJob).toBeNull();
    expect(component.viewMode).toBe('list');
  });

  it('deleteJob should NOT call deleteJob when dialog is cancelled', () => {
    dialogSpy.open.and.returnValue({
      afterClosed: () => of(false)
    } as MatDialogRef<any>);
    const mockEvent = { stopPropagation: jasmine.createSpy('stopPropagation') } as any as Event;
    component.deleteJob('task-001', mockEvent);
    expect(jobHistorySpy.deleteJob).not.toHaveBeenCalled();
  });

  it('deleteJob should set error when deletion fails', () => {
    dialogSpy.open.and.returnValue({
      afterClosed: () => of(true)
    } as MatDialogRef<any>);
    jobHistorySpy.deleteJob.and.returnValue(throwError(() => new Error('delete failed')));
    component.jobs = [makeJob({ taskId: 'task-fail' })];

    const mockEvent = { stopPropagation: jasmine.createSpy('stopPropagation') } as any as Event;
    component.deleteJob('task-fail', mockEvent);

    expect(component.error).toContain('task-fail');
  });

  // ===================== UTILITY / FORMATTER DELEGATES =====================

  it('formatDuration should delegate to service', () => {
    component.formatDuration(1500);
    expect(jobHistorySpy.formatDuration).toHaveBeenCalledWith(1500);
  });

  it('formatBytes should delegate to service', () => {
    component.formatBytes(2048);
    expect(jobHistorySpy.formatBytes).toHaveBeenCalledWith(2048);
  });

  it('formatTimestamp should delegate to service', () => {
    component.formatTimestamp('2025-01-01T00:00:00Z');
    expect(jobHistorySpy.formatTimestamp).toHaveBeenCalledWith('2025-01-01T00:00:00Z');
  });

  it('getStatusClass should delegate to service', () => {
    component.getStatusClass('COMPLETED');
    expect(jobHistorySpy.getStatusClass).toHaveBeenCalledWith('COMPLETED');
  });

  it('getStatusIcon should delegate to service', () => {
    component.getStatusIcon('FAILED');
    expect(jobHistorySpy.getStatusIcon).toHaveBeenCalledWith('FAILED');
  });

  it('getPhaseIcon should delegate to service', () => {
    component.getPhaseIcon('EMBEDDING');
    expect(jobHistorySpy.getPhaseIcon).toHaveBeenCalledWith('EMBEDDING');
  });

  it('getSubprocessEventIcon should delegate to service', () => {
    component.getSubprocessEventIcon('SUBPROCESS_STARTED');
    expect(jobHistorySpy.getSubprocessEventIcon).toHaveBeenCalledWith('SUBPROCESS_STARTED');
  });

  it('getSubprocessEventClass should delegate to service', () => {
    component.getSubprocessEventClass('MODEL_LOADED');
    expect(jobHistorySpy.getSubprocessEventClass).toHaveBeenCalledWith('MODEL_LOADED');
  });

  it('formatSubprocessEventType should delegate to service', () => {
    component.formatSubprocessEventType('MODEL_FAILED');
    expect(jobHistorySpy.formatSubprocessEventType).toHaveBeenCalledWith('MODEL_FAILED');
  });

  // ===================== getProgressBarClass =====================

  it('getProgressBarClass should return progress-success for COMPLETED', () => {
    expect(component.getProgressBarClass(makeJob({ status: 'COMPLETED' }))).toBe('progress-success');
  });

  it('getProgressBarClass should return progress-error for FAILED', () => {
    expect(component.getProgressBarClass(makeJob({ status: 'FAILED' }))).toBe('progress-error');
  });

  it('getProgressBarClass should return progress-error for MEMORY_KILLED', () => {
    expect(component.getProgressBarClass(makeJob({ status: 'MEMORY_KILLED' }))).toBe('progress-error');
  });

  it('getProgressBarClass should return progress-warning for CANCELLED', () => {
    expect(component.getProgressBarClass(makeJob({ status: 'CANCELLED' }))).toBe('progress-warning');
  });

  it('getProgressBarClass should return progress-info for RUNNING', () => {
    expect(component.getProgressBarClass(makeJob({ status: 'RUNNING' }))).toBe('progress-info');
  });

  // ===================== getFailureReasonDisplay =====================

  it('getFailureReasonDisplay should return empty string for undefined', () => {
    expect(component.getFailureReasonDisplay(undefined)).toBe('');
  });

  it('getFailureReasonDisplay should return empty string for NONE', () => {
    expect(component.getFailureReasonDisplay('NONE')).toBe('');
  });

  it('getFailureReasonDisplay should format OUT_OF_MEMORY correctly', () => {
    expect(component.getFailureReasonDisplay('OUT_OF_MEMORY')).toBe('Out Of Memory');
  });

  it('getFailureReasonDisplay should format EMBEDDING_ERROR correctly', () => {
    expect(component.getFailureReasonDisplay('EMBEDDING_ERROR')).toBe('Embedding Error');
  });

  // ===================== getJobTypeDisplay / getJobTypeIcon =====================

  it('getJobTypeDisplay should return "Vector Population" for vector-population', () => {
    expect(component.getJobTypeDisplay('vector-population')).toBe('Vector Population');
  });

  it('getJobTypeDisplay should return "Embedding Model" for embedding', () => {
    expect(component.getJobTypeDisplay('embedding')).toBe('Embedding Model');
  });

  it('getJobTypeDisplay should return "Subprocess Event" for subprocess', () => {
    expect(component.getJobTypeDisplay('subprocess')).toBe('Subprocess Event');
  });

  it('getJobTypeDisplay should return "Document Ingest" for unknown/undefined', () => {
    expect(component.getJobTypeDisplay(undefined)).toBe('Document Ingest');
    expect(component.getJobTypeDisplay('other')).toBe('Document Ingest');
  });

  it('getJobTypeIcon should return correct icons', () => {
    expect(component.getJobTypeIcon('vector-population')).toBe('storage');
    expect(component.getJobTypeIcon('embedding')).toBe('model_training');
    expect(component.getJobTypeIcon('subprocess')).toBe('autorenew');
    expect(component.getJobTypeIcon(undefined)).toBe('upload_file');
  });

  // ===================== RESTART EVENT HELPERS =====================

  it('isRestartEvent should return true for restart-related event types', () => {
    const restartTypes = [
      'RESTART_SCHEDULED', 'RESTART_ATTEMPTED', 'RESTART_SUCCEEDED',
      'RESTART_FAILED', 'MEMORY_ANALYSIS', 'HEAP_ADJUSTED',
      'THREADS_REDUCED', 'MANUAL_RESTART'
    ];
    restartTypes.forEach(type => {
      expect(component.isRestartEvent(type)).withContext(`expected ${type} to be restart event`).toBeTrue();
    });
  });

  it('isRestartEvent should return false for non-restart event types', () => {
    expect(component.isRestartEvent('STARTED')).toBeFalse();
    expect(component.isRestartEvent('COMPLETED')).toBeFalse();
    expect(component.isRestartEvent('PROGRESS')).toBeFalse();
  });

  it('getRestartEventCount should count restart events in events list', () => {
    component.events = [
      makeIngestEvent({ eventType: 'RESTART_SCHEDULED' }),
      makeIngestEvent({ eventType: 'STARTED' }),
      makeIngestEvent({ eventType: 'RESTART_SUCCEEDED' }),
      makeIngestEvent({ eventType: 'COMPLETED' }),
    ];
    expect(component.getRestartEventCount()).toBe(2);
  });

  it('getRestartEventIcon should return correct icons', () => {
    expect(component.getRestartEventIcon('RESTART_SCHEDULED')).toBe('schedule');
    expect(component.getRestartEventIcon('RESTART_ATTEMPTED')).toBe('autorenew');
    expect(component.getRestartEventIcon('RESTART_SUCCEEDED')).toBe('check_circle');
    expect(component.getRestartEventIcon('RESTART_FAILED')).toBe('error');
    expect(component.getRestartEventIcon('MEMORY_ANALYSIS')).toBe('memory');
    expect(component.getRestartEventIcon('HEAP_ADJUSTED')).toBe('tune');
    expect(component.getRestartEventIcon('THREADS_REDUCED')).toBe('compress');
    expect(component.getRestartEventIcon('MANUAL_RESTART')).toBe('replay');
    expect(component.getRestartEventIcon('UNKNOWN')).toBe('info');
  });

  it('getEventTypeClass should include restart-pending for RESTART_SCHEDULED', () => {
    const cls = component.getEventTypeClass('RESTART_SCHEDULED');
    expect(cls).toContain('restart-pending');
  });

  it('getEventTypeClass should include restart-success for RESTART_SUCCEEDED', () => {
    const cls = component.getEventTypeClass('RESTART_SUCCEEDED');
    expect(cls).toContain('restart-success');
  });

  it('getEventTypeClass should include restart-failed for RESTART_FAILED', () => {
    const cls = component.getEventTypeClass('RESTART_FAILED');
    expect(cls).toContain('restart-failed');
  });

  it('getEventTypeClass should include memory-event for HEAP_ADJUSTED', () => {
    const cls = component.getEventTypeClass('HEAP_ADJUSTED');
    expect(cls).toContain('memory-event');
  });

  // ===================== SUBPROCESS EVENT HELPERS =====================

  it('isSubprocessView should return true when jobTypeFilter=subprocess', () => {
    component.jobTypeFilter = 'subprocess';
    expect(component.isSubprocessView()).toBeTrue();
  });

  it('isSubprocessView should return false otherwise', () => {
    component.jobTypeFilter = 'ALL';
    expect(component.isSubprocessView()).toBeFalse();
  });

  it('getSubprocessRestartCount should return totalRestartAttempts from stats', () => {
    component.subprocessStatistics = makeSubprocessStats({ totalRestartAttempts: 7 });
    expect(component.getSubprocessRestartCount()).toBe(7);
  });

  it('getSubprocessRestartCount should return 0 when stats is null', () => {
    component.subprocessStatistics = null;
    expect(component.getSubprocessRestartCount()).toBe(0);
  });

  it('getSubprocessCrashCount should return totalCrashes from stats', () => {
    component.subprocessStatistics = makeSubprocessStats({ totalCrashes: 3 });
    expect(component.getSubprocessCrashCount()).toBe(3);
  });

  it('getSubprocessRestartSuccessRate should return restartSuccessRate from stats', () => {
    component.subprocessStatistics = makeSubprocessStats({ restartSuccessRate: 80 });
    expect(component.getSubprocessRestartSuccessRate()).toBe(80);
  });

  it('getFilteredSubprocessEvents should return all events when no searchTerm', () => {
    component.searchTerm = '';
    component.subprocessEvents = [makeSubprocessEvent({ id: 1 }), makeSubprocessEvent({ id: 2 })];
    expect(component.getFilteredSubprocessEvents().length).toBe(2);
  });

  it('getFilteredSubprocessEvents should filter by modelId search term', () => {
    component.searchTerm = 'bge';
    component.subprocessEvents = [
      makeSubprocessEvent({ id: 1, modelId: 'bge-base-en' }),
      makeSubprocessEvent({ id: 2, modelId: 'arctic-embed' }),
    ];
    const result = component.getFilteredSubprocessEvents();
    expect(result.length).toBe(1);
    expect(result[0].id).toBe(1);
  });

  // ===================== CONVERT SUBPROCESS EVENTS =====================

  it('convertSubprocessEventsToJobs should produce IndexingJobHistory array', () => {
    const events = [makeSubprocessEvent({ id: 5, eventType: 'SUBPROCESS_CRASHED' })];
    const converted = component.convertSubprocessEventsToJobs(events);
    expect(converted.length).toBe(1);
    expect(converted[0].status).toBe('FAILED');
    expect(converted[0].contentType).toBe('subprocess');
  });

  it('convertSubprocessEventsToJobs SUBPROCESS_STARTED maps to COMPLETED', () => {
    const events = [makeSubprocessEvent({ id: 1, eventType: 'SUBPROCESS_STARTED' })];
    expect(component.convertSubprocessEventsToJobs(events)[0].status).toBe('COMPLETED');
  });

  it('convertSubprocessEventsToJobs SUBPROCESS_STOPPED maps to CANCELLED', () => {
    const events = [makeSubprocessEvent({ id: 2, eventType: 'SUBPROCESS_STOPPED' })];
    expect(component.convertSubprocessEventsToJobs(events)[0].status).toBe('CANCELLED');
  });

  it('convertSubprocessEventsToJobs SUBPROCESS_RESTARTING maps to RUNNING', () => {
    const events = [makeSubprocessEvent({ id: 3, eventType: 'SUBPROCESS_RESTARTING' })];
    expect(component.convertSubprocessEventsToJobs(events)[0].status).toBe('RUNNING');
  });

  // ===================== MERGED JOBS =====================

  it('getMergedJobsAndSubprocessEvents returns only subprocess jobs when filter=subprocess', () => {
    component.jobTypeFilter = 'subprocess';
    component.jobs = [makeJob({ taskId: 'regular-1' })];
    component.subprocessEvents = [makeSubprocessEvent({ id: 10 })];
    const merged = component.getMergedJobsAndSubprocessEvents();
    expect(merged.length).toBe(1);
    expect(merged[0].contentType).toBe('subprocess');
  });

  it('getMergedJobsAndSubprocessEvents returns only regular jobs when filter != ALL and != subprocess', () => {
    component.jobTypeFilter = 'ingest';
    component.jobs = [makeJob({ taskId: 'regular-1' })];
    component.subprocessEvents = [makeSubprocessEvent({ id: 10 })];
    const merged = component.getMergedJobsAndSubprocessEvents();
    expect(merged.length).toBe(1);
    expect(merged[0].taskId).toBe('regular-1');
  });

  it('getMergedJobsAndSubprocessEvents merges and sorts by timestamp for ALL', () => {
    component.jobTypeFilter = 'ALL';
    const older = makeJob({ taskId: 'old', startTime: new Date(Date.now() - 10000).toISOString() });
    const newer = makeJob({ taskId: 'new', startTime: new Date().toISOString() });
    component.jobs = [older, newer];
    component.subprocessEvents = [];
    const merged = component.getMergedJobsAndSubprocessEvents();
    expect(merged[0].taskId).toBe('new');
    expect(merged[1].taskId).toBe('old');
  });

  // ===================== PAGINATION =====================

  it('paginatedJobs should return at most pageSize jobs', () => {
    component.pageSize = 5;
    component.currentPage = 0;
    component.jobs = Array.from({ length: 10 }, (_, i) =>
      makeJob({ taskId: `task-${i}`, fileName: `file-${i}.pdf` })
    );
    component.subprocessEvents = [];
    component.jobTypeFilter = 'ALL';
    component.searchTerm = '';
    expect(component.paginatedJobs.length).toBe(5);
  });

  it('totalPages should calculate correctly', () => {
    component.pageSize = 5;
    component.jobs = Array.from({ length: 13 }, (_, i) =>
      makeJob({ taskId: `task-${i}`, fileName: `file-${i}.pdf` })
    );
    component.subprocessEvents = [];
    component.jobTypeFilter = 'ALL';
    component.searchTerm = '';
    expect(component.totalPages).toBe(3);
  });

  it('nextPage should increment currentPage if not on last page', () => {
    component.pageSize = 5;
    component.currentPage = 0;
    component.jobs = Array.from({ length: 10 }, (_, i) =>
      makeJob({ taskId: `task-${i}`, fileName: `file-${i}.pdf` })
    );
    component.subprocessEvents = [];
    component.jobTypeFilter = 'ALL';
    component.searchTerm = '';
    component.nextPage();
    expect(component.currentPage).toBe(1);
  });

  it('nextPage should not increment beyond last page', () => {
    component.pageSize = 10;
    component.currentPage = 0;
    component.jobs = [makeJob()];
    component.subprocessEvents = [];
    component.jobTypeFilter = 'ALL';
    component.searchTerm = '';
    component.nextPage();
    expect(component.currentPage).toBe(0);
  });

  it('prevPage should decrement currentPage if not on first page', () => {
    component.currentPage = 2;
    component.pageSize = 5;
    component.jobs = Array.from({ length: 20 }, (_, i) =>
      makeJob({ taskId: `task-${i}`, fileName: `file-${i}.pdf` })
    );
    component.subprocessEvents = [];
    component.jobTypeFilter = 'ALL';
    component.searchTerm = '';
    component.prevPage();
    expect(component.currentPage).toBe(1);
  });

  it('prevPage should not go below 0', () => {
    component.currentPage = 0;
    component.prevPage();
    expect(component.currentPage).toBe(0);
  });

  // ===================== TRACK BY FUNCTIONS =====================

  it('trackByTaskId should return taskId', () => {
    const job = makeJob({ taskId: 'task-abc' });
    expect(component.trackByTaskId(0, job)).toBe('task-abc');
  });

  it('trackBySubprocessEventId should return event.id', () => {
    const event = makeSubprocessEvent({ id: 42 });
    expect(component.trackBySubprocessEventId(0, event)).toBe(42);
  });

  it('trackByEventIndex should return composite key', () => {
    const event = makeIngestEvent({ timestamp: '2025-01-01T00:00:00Z', eventType: 'STARTED' });
    const key = component.trackByEventIndex(3, event);
    expect(key).toBe('2025-01-01T00:00:00Z-STARTED-3');
  });

  // ===================== ND4J DETAILS =====================

  it('toggleNd4jDetails should toggle showNd4jDetails', () => {
    component.showNd4jDetails = false;
    component.toggleNd4jDetails();
    expect(component.showNd4jDetails).toBeTrue();
    component.toggleNd4jDetails();
    expect(component.showNd4jDetails).toBeFalse();
  });

  it('getNd4jConfigCategories should return [] when parsedNd4jEnvironment is null', () => {
    component.parsedNd4jEnvironment = null;
    expect(component.getNd4jConfigCategories()).toEqual([]);
  });

  it('getNd4jConfigCategories should return categories when environment is populated', () => {
    component.parsedNd4jEnvironment = {
      blasMajorOrderThreads: 4,
      debug: true,
      maxPrimaryMemory: 1073741824
    };
    const categories = component.getNd4jConfigCategories();
    expect(categories.length).toBeGreaterThan(0);
    const names = categories.map(c => c.name);
    expect(names).toContain('Threading');
    expect(names).toContain('Debug');
    expect(names).toContain('Memory Limits');
  });

  it('getNd4jConfigCategories should use cached result on repeated calls', () => {
    component.parsedNd4jEnvironment = { debug: true };
    const first = component.getNd4jConfigCategories();
    const second = component.getNd4jConfigCategories();
    expect(first).toBe(second); // same reference (cached)
  });

  // ===================== ngOnDestroy =====================

  it('ngOnDestroy should complete destroy$ and progressUpdates$', () => {
    fixture.detectChanges();
    const destroySpy = spyOn<any>(component['destroy$'], 'next').and.callThrough();
    const completeSpy = spyOn<any>(component['destroy$'], 'complete').and.callThrough();
    component.ngOnDestroy();
    expect(destroySpy).toHaveBeenCalled();
    expect(completeSpy).toHaveBeenCalled();
  });

  it('should not throw when ngOnDestroy is called multiple times', () => {
    fixture.detectChanges();
    expect(() => {
      component.ngOnDestroy();
      component.ngOnDestroy();
    }).not.toThrow();
  });

  // ═══════════════════════ EDGE CASE TESTS ═══════════════════════

  describe('getProgressBarClass() – edge cases', () => {
    it('should return "progress-success" for COMPLETED', () => {
      const job = { status: 'COMPLETED' } as any;
      expect(component.getProgressBarClass(job)).toBe('progress-success');
    });

    it('should return "progress-error" for FAILED', () => {
      const job = { status: 'FAILED' } as any;
      expect(component.getProgressBarClass(job)).toBe('progress-error');
    });

    it('should return "progress-error" for MEMORY_KILLED', () => {
      const job = { status: 'MEMORY_KILLED' } as any;
      expect(component.getProgressBarClass(job)).toBe('progress-error');
    });

    it('should return "progress-warning" for CANCELLED', () => {
      const job = { status: 'CANCELLED' } as any;
      expect(component.getProgressBarClass(job)).toBe('progress-warning');
    });

    it('should return "progress-info" for QUEUED (default)', () => {
      const job = { status: 'QUEUED' } as any;
      expect(component.getProgressBarClass(job)).toBe('progress-info');
    });

    it('should return "progress-info" for PAUSED (default)', () => {
      const job = { status: 'PAUSED' } as any;
      expect(component.getProgressBarClass(job)).toBe('progress-info');
    });

    it('should return "progress-info" for unknown status', () => {
      const job = { status: 'SOMETHING_ELSE' } as any;
      expect(component.getProgressBarClass(job)).toBe('progress-info');
    });
  });

  describe('getJobTypeDisplay() – edge cases', () => {
    it('should return "Document Ingest" for undefined contentType', () => {
      expect(component.getJobTypeDisplay(undefined)).toBe('Document Ingest');
    });

    it('should return "Document Ingest" for empty string', () => {
      expect(component.getJobTypeDisplay('')).toBe('Document Ingest');
    });

    it('should return "Vector Population" for vector-population', () => {
      expect(component.getJobTypeDisplay('vector-population')).toBe('Vector Population');
    });

    it('should return "Embedding Model" for embedding', () => {
      expect(component.getJobTypeDisplay('embedding')).toBe('Embedding Model');
    });

    it('should return "Subprocess Event" for subprocess', () => {
      expect(component.getJobTypeDisplay('subprocess')).toBe('Subprocess Event');
    });

    it('should return "Document Ingest" for unknown contentType', () => {
      expect(component.getJobTypeDisplay('unknown-type')).toBe('Document Ingest');
    });
  });

  describe('getJobTypeIcon() – edge cases', () => {
    it('should return "upload_file" for undefined', () => {
      expect(component.getJobTypeIcon(undefined)).toBe('upload_file');
    });

    it('should return "upload_file" for empty string', () => {
      expect(component.getJobTypeIcon('')).toBe('upload_file');
    });

    it('should return "storage" for vector-population', () => {
      expect(component.getJobTypeIcon('vector-population')).toBe('storage');
    });

    it('should return "model_training" for embedding', () => {
      expect(component.getJobTypeIcon('embedding')).toBe('model_training');
    });

    it('should return "autorenew" for subprocess', () => {
      expect(component.getJobTypeIcon('subprocess')).toBe('autorenew');
    });
  });

  describe('filterJobs() – edge cases', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should return empty array for empty input', () => {
      expect(component.filterJobs([])).toEqual([]);
    });

    it('should handle searchTerm with special regex characters', () => {
      component.searchTerm = '(test)';
      const jobs = [{ fileName: 'test(test).pdf', taskId: 't1' } as any];
      // Should not throw — uses .includes() not regex
      expect(() => component.filterJobs(jobs)).not.toThrow();
    });

    it('should match searchTerm case-insensitively', () => {
      component.searchTerm = 'DOCUMENT';
      const jobs = [
        { fileName: 'My Document.pdf', taskId: 't1', contentType: 'ingest' } as any,
        { fileName: 'other.pdf', taskId: 't2', contentType: 'ingest' } as any
      ];
      const result = component.filterJobs(jobs);
      expect(result.length).toBe(1);
      expect(result[0].fileName).toBe('My Document.pdf');
    });

    it('should handle undefined optional fields in search', () => {
      component.searchTerm = 'missing';
      const jobs = [{
        fileName: 'test.pdf',
        taskId: 't1',
        loaderUsed: undefined,
        embeddingModelUsed: undefined,
        contentType: 'ingest'
      } as any];
      // Should not throw even with undefined fields
      expect(() => component.filterJobs(jobs)).not.toThrow();
    });
  });

  describe('paginatedJobs – edge cases', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should return empty array when jobs is empty', () => {
      component.jobs = [];
      expect(component.paginatedJobs).toEqual([]);
    });

    it('should return at most pageSize items', () => {
      component.pageSize = 2;
      component.currentPage = 0;
      component.jobs = [
        { taskId: 't1', fileName: 'a.pdf', startTime: '2025-01-01T00:00:00Z' } as any,
        { taskId: 't2', fileName: 'b.pdf', startTime: '2025-01-02T00:00:00Z' } as any,
        { taskId: 't3', fileName: 'c.pdf', startTime: '2025-01-03T00:00:00Z' } as any
      ];
      expect(component.paginatedJobs.length).toBeLessThanOrEqual(2);
    });

    it('should return empty array when currentPage exceeds data', () => {
      component.pageSize = 10;
      component.currentPage = 100;
      component.jobs = [{ taskId: 't1', fileName: 'a.pdf', startTime: '2025-01-01T00:00:00Z' } as any];
      expect(component.paginatedJobs).toEqual([]);
    });
  });

  describe('getSubprocessRestartCount() – edge cases', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should return 0 when subprocessStatistics is null', () => {
      component.subprocessStatistics = null;
      expect(component.getSubprocessRestartCount()).toBe(0);
    });

    it('should return 0 when totalRestartAttempts is undefined', () => {
      component.subprocessStatistics = { available: true } as any;
      expect(component.getSubprocessRestartCount()).toBe(0);
    });

    it('should return correct value when set', () => {
      component.subprocessStatistics = { available: true, totalRestartAttempts: 5 } as any;
      expect(component.getSubprocessRestartCount()).toBe(5);
    });
  });
});
