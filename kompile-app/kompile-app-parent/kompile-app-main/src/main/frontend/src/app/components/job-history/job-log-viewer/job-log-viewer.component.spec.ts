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

import { ComponentFixture, TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NO_ERRORS_SCHEMA, ChangeDetectorRef } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, Subject, throwError } from 'rxjs';

import { JobLogViewerComponent } from './job-log-viewer.component';
import { JobLogService, JobLogEntry, LogLevel, JobLogsResponse, LogTailResponse, LogCountResponse, ArchivedLogsResponse } from '../../../services/job-log.service';
import { WebSocketService } from '../../../services/websocket.service';
import { IngestLogEntry } from '../../../models/api-models';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function makeJobLogEntry(overrides: Partial<JobLogEntry> = {}): JobLogEntry {
  return {
    id: 1,
    taskId: 'task-1',
    timestamp: '2025-01-01T10:00:00.000Z',
    level: 'INFO' as LogLevel,
    source: 'STDOUT',
    message: 'Test log message',
    sequenceNumber: 1,
    ...overrides
  } as JobLogEntry;
}

function makeJobLogsResponse(overrides: Partial<JobLogsResponse> = {}): JobLogsResponse {
  return {
    enabled: true,
    taskId: 'task-1',
    logs: [makeJobLogEntry()],
    totalCount: 1,
    page: 0,
    size: 100,
    levelCounts: { INFO: 1 },
    ...overrides
  };
}

function makeLogTailResponse(logs: JobLogEntry[] = []): LogTailResponse {
  return {
    enabled: true,
    taskId: 'task-1',
    logs,
    count: logs.length
  };
}

function makeArchivedLogsResponse(available: boolean, logs: any[] = []): ArchivedLogsResponse {
  return {
    available,
    taskId: 'task-1',
    logs,
    totalCount: logs.length,
    source: 'archive'
  };
}

function makeIngestLogEntry(overrides: Partial<IngestLogEntry> = {}): IngestLogEntry {
  return {
    taskId: 'task-1',
    level: 'INFO',
    source: 'STDOUT',
    message: 'Streaming log',
    timestamp: '2025-01-01T10:00:01.000Z',
    sequenceNumber: 10,
    ...overrides
  } as IngestLogEntry;
}

// ─── Suite ────────────────────────────────────────────────────────────────────

describe('JobLogViewerComponent', () => {
  let component: JobLogViewerComponent;
  let fixture: ComponentFixture<JobLogViewerComponent>;
  let jobLogServiceSpy: jasmine.SpyObj<JobLogService>;
  let webSocketServiceSpy: jasmine.SpyObj<WebSocketService>;

  beforeEach(async () => {
    jobLogServiceSpy = jasmine.createSpyObj('JobLogService', [
      'getLogsForJob',
      'tailLogs',
      'getLogCount',
      'getArchivedLogs',
      'downloadLogsAsFile',
      'getLevelClass',
      'getLevelIcon',
      'formatTimestamp'
    ]);

    webSocketServiceSpy = jasmine.createSpyObj('WebSocketService', [
      'connect',
      'subscribeToTaskLogs',
      'subscribeToVectorPopulationLogs',
      'unsubscribeFromTaskLogs',
      'unsubscribeFromVectorPopulationLogs'
    ]);

    // Default return values
    jobLogServiceSpy.getLogsForJob.and.returnValue(of(makeJobLogsResponse()));
    jobLogServiceSpy.tailLogs.and.returnValue(of(makeLogTailResponse()));
    jobLogServiceSpy.getLogCount.and.returnValue(of({ enabled: true, taskId: 'task-1', count: 0, levelCounts: {} }));
    jobLogServiceSpy.getArchivedLogs.and.returnValue(of(makeArchivedLogsResponse(false)));
    jobLogServiceSpy.getLevelClass.and.callFake((level: LogLevel) => `log-${level.toLowerCase()}`);
    jobLogServiceSpy.getLevelIcon.and.callFake((level: LogLevel) => level === 'INFO' ? 'info' : 'warning');
    jobLogServiceSpy.formatTimestamp.and.callFake((ts: string) => ts);

    webSocketServiceSpy.subscribeToTaskLogs.and.returnValue(of());
    webSocketServiceSpy.subscribeToVectorPopulationLogs.and.returnValue(of());

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
      .overrideComponent(JobLogViewerComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .overrideProvider(JobLogService, { useValue: jobLogServiceSpy })
      .overrideProvider(WebSocketService, { useValue: webSocketServiceSpy })
      .compileComponents();

    fixture = TestBed.createComponent(JobLogViewerComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    component.ngOnDestroy();
  });

  // ── 1. Creation ─────────────────────────────────────────────────────────────

  it('should create', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('should have default input values', () => {
    expect(component.taskId).toBe('');
    expect(component.isJobRunning).toBe(false);
    expect(component.logSource).toBe('ingest');
    expect(component.maxTailLogs).toBe(200);
    expect(component.maxArchiveLogs).toBe(5000);
  });

  it('should start with INFO, WARN, ERROR levels selected', () => {
    expect(component.selectedLevels.has('INFO')).toBeTrue();
    expect(component.selectedLevels.has('WARN')).toBeTrue();
    expect(component.selectedLevels.has('ERROR')).toBeTrue();
    expect(component.selectedLevels.has('DEBUG')).toBeFalse();
    expect(component.selectedLevels.has('TRACE')).toBeFalse();
  });

  it('should expose all five log levels', () => {
    expect(component.allLevels).toEqual(['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR']);
  });

  // ── 2. ngOnInit – no taskId ──────────────────────────────────────────────────

  it('should not load logs when taskId is empty on init', () => {
    component.taskId = '';
    fixture.detectChanges();
    expect(jobLogServiceSpy.getLogsForJob).not.toHaveBeenCalled();
  });

  // ── 3. ngOnInit – completed job (isJobRunning = false) ───────────────────────

  it('should call getLogCount then getLogsForJob for a completed job', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = false;
    jobLogServiceSpy.getLogCount.and.returnValue(of({ enabled: true, taskId: 'task-1', count: 100, levelCounts: {} }));
    jobLogServiceSpy.getLogsForJob.and.returnValue(of(makeJobLogsResponse({ totalCount: 100 })));

    fixture.detectChanges();
    tick(500);
    discardPeriodicTasks();

    expect(jobLogServiceSpy.getLogCount).toHaveBeenCalledWith('task-1');
    expect(jobLogServiceSpy.getLogsForJob).toHaveBeenCalled();
  }));

  it('should populate logs array after getLogsForJob resolves', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = false;
    jobLogServiceSpy.getLogCount.and.returnValue(of({ enabled: true, taskId: 'task-1', count: 1, levelCounts: {} }));
    jobLogServiceSpy.getLogsForJob.and.returnValue(of(makeJobLogsResponse()));

    fixture.detectChanges();
    tick(500);
    discardPeriodicTasks();

    expect(component.logs.length).toBe(1);
    expect(component.totalCount).toBe(1);
  }));

  // ── 4. ngOnInit – running job (isJobRunning = true) ──────────────────────────

  it('should call tailLogs and then connect WebSocket when job is running', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = true;
    const tailLog = makeJobLogEntry({ level: 'INFO', sequenceNumber: 5 });
    jobLogServiceSpy.tailLogs.and.returnValue(of(makeLogTailResponse([tailLog])));

    fixture.detectChanges();
    tick(500);
    discardPeriodicTasks();

    expect(jobLogServiceSpy.tailLogs).toHaveBeenCalledWith('task-1', component.maxTailLogs);
    expect(webSocketServiceSpy.connect).toHaveBeenCalled();
  }));

  it('should set tailMode = true when job is running on init', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = true;
    jobLogServiceSpy.tailLogs.and.returnValue(of(makeLogTailResponse([])));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    expect(component.tailMode).toBeTrue();
  }));

  // ── 5. loadLogs – pagination ─────────────────────────────────────────────────

  it('should set currentPage to 0 and call fetchLogsPage when loadLogs called without startFromLastPage', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = false;
    fixture.detectChanges();
    tick(500);
    discardPeriodicTasks();

    // Call again explicitly to ensure non-first-page init
    component['initialLoadComplete'] = true;
    component.loadLogs();
    tick(100);
    discardPeriodicTasks();

    expect(jobLogServiceSpy.getLogsForJob).toHaveBeenCalled();
  }));

  it('should go to nextPage and call loadLogs', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = false;
    component['initialLoadComplete'] = true;
    jobLogServiceSpy.getLogCount.and.returnValue(of({ enabled: true, taskId: 'task-1', count: 250, levelCounts: {} }));
    jobLogServiceSpy.getLogsForJob.and.returnValue(of(makeJobLogsResponse({ totalCount: 250 })));

    fixture.detectChanges();
    tick(500);
    discardPeriodicTasks();

    // After initial load, totalCount should be 250 from the mocked response
    component.totalCount = 250;
    component.currentPage = 0;
    jobLogServiceSpy.getLogsForJob.calls.reset();

    component.nextPage();
    tick(200);
    discardPeriodicTasks();

    expect(component.currentPage).toBe(1);
    expect(jobLogServiceSpy.getLogsForJob).toHaveBeenCalled();
  }));

  it('should not advance nextPage beyond last page', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.totalCount = 50;
    component.currentPage = 0;
    component.pageSize = 100;

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    jobLogServiceSpy.getLogsForJob.calls.reset();
    component.nextPage();
    tick(100);
    discardPeriodicTasks();

    // currentPage should still be 0 because 0*100+100 >= 50
    expect(component.currentPage).toBe(0);
    expect(jobLogServiceSpy.getLogsForJob).not.toHaveBeenCalled();
  }));

  it('should go to prevPage', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    jobLogServiceSpy.getLogCount.and.returnValue(of({ enabled: true, taskId: 'task-1', count: 300, levelCounts: {} }));
    jobLogServiceSpy.getLogsForJob.and.returnValue(of(makeJobLogsResponse({ totalCount: 300 })));

    fixture.detectChanges();
    tick(500);
    discardPeriodicTasks();

    // Force to page 2 after initial load settles
    component.currentPage = 2;
    component.totalCount = 300;
    jobLogServiceSpy.getLogsForJob.calls.reset();

    component.prevPage();
    tick(200);
    discardPeriodicTasks();

    expect(component.currentPage).toBe(1);
    expect(jobLogServiceSpy.getLogsForJob).toHaveBeenCalled();
  }));

  it('should not go below page 0 on prevPage', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.currentPage = 0;

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    jobLogServiceSpy.getLogsForJob.calls.reset();
    component.prevPage();
    tick(100);
    discardPeriodicTasks();

    expect(component.currentPage).toBe(0);
    expect(jobLogServiceSpy.getLogsForJob).not.toHaveBeenCalled();
  }));

  it('should navigate to firstPage', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.currentPage = 3;
    component.totalCount = 500;

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    jobLogServiceSpy.getLogsForJob.calls.reset();
    component.firstPage();
    tick(100);
    discardPeriodicTasks();

    expect(component.currentPage).toBe(0);
    expect(jobLogServiceSpy.getLogsForJob).toHaveBeenCalled();
  }));

  it('should navigate to lastPage', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    jobLogServiceSpy.getLogCount.and.returnValue(of({ enabled: true, taskId: 'task-1', count: 350, levelCounts: {} }));
    jobLogServiceSpy.getLogsForJob.and.returnValue(of(makeJobLogsResponse({ totalCount: 350 })));

    fixture.detectChanges();
    tick(500);
    discardPeriodicTasks();

    // After load settles, set state so we can test lastPage
    component.totalCount = 350;
    component.pageSize = 100;
    component.currentPage = 0;
    jobLogServiceSpy.getLogsForJob.calls.reset();

    component.lastPage();
    tick(200);
    discardPeriodicTasks();

    expect(component.currentPage).toBe(3);
  }));

  it('should compute totalPages correctly', () => {
    component.totalCount = 250;
    component.pageSize = 100;
    expect(component.totalPages).toBe(3);
  });

  it('should compute hasNextPage correctly', () => {
    component.totalCount = 250;
    component.pageSize = 100;
    component.currentPage = 1;
    expect(component.hasNextPage).toBeTrue();
    component.currentPage = 2;
    expect(component.hasNextPage).toBeFalse();
  });

  it('should compute hasPrevPage correctly', () => {
    component.currentPage = 0;
    expect(component.hasPrevPage).toBeFalse();
    component.currentPage = 1;
    expect(component.hasPrevPage).toBeTrue();
  });

  it('should compute displayedRange correctly', () => {
    component.currentPage = 1;
    component.pageSize = 100;
    component.totalCount = 250;
    expect(component.displayedRange).toBe('101-200 of 250');
  });

  // ── 6. Archive fallback ───────────────────────────────────────────────────────

  it('should try archive when live logs are empty and job is not running', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = false;
    component['initialLoadComplete'] = true;

    jobLogServiceSpy.getLogsForJob.and.returnValue(of(makeJobLogsResponse({ logs: [], totalCount: 0 })));
    const archivedLog = {
      taskId: 'task-1',
      timestamp: '2025-01-01T10:00:00.000Z',
      level: 'INFO',
      source: 'STDOUT',
      message: 'archived log',
      sequenceNumber: 1
    };
    jobLogServiceSpy.getArchivedLogs.and.returnValue(of(makeArchivedLogsResponse(true, [archivedLog])));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.loadLogs();
    tick(200);
    discardPeriodicTasks();

    expect(jobLogServiceSpy.getArchivedLogs).toHaveBeenCalledWith('task-1');
    expect(component.loadedFromArchive).toBeTrue();
    expect(component.logs.length).toBe(1);
  }));

  it('should truncate archive logs when they exceed maxArchiveLogs', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = false;
    component['initialLoadComplete'] = true;
    component.maxArchiveLogs = 2;

    const archivedLogs = [1, 2, 3, 4, 5].map(i => ({
      taskId: 'task-1',
      timestamp: '2025-01-01T10:00:00.000Z',
      level: 'INFO',
      source: 'STDOUT',
      message: `log ${i}`,
      sequenceNumber: i
    }));

    jobLogServiceSpy.getLogsForJob.and.returnValue(of(makeJobLogsResponse({ logs: [], totalCount: 0 })));
    jobLogServiceSpy.getArchivedLogs.and.returnValue(of(makeArchivedLogsResponse(true, archivedLogs)));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.loadLogs();
    tick(200);
    discardPeriodicTasks();

    expect(component.logs.length).toBe(2);
    expect(component.totalCount).toBe(5); // shows actual total
  }));

  it('should show empty state when archive is also unavailable', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = false;
    component['initialLoadComplete'] = true;

    jobLogServiceSpy.getLogsForJob.and.returnValue(of(makeJobLogsResponse({ logs: [], totalCount: 0 })));
    jobLogServiceSpy.getArchivedLogs.and.returnValue(of(makeArchivedLogsResponse(false, [])));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.loadLogs();
    tick(200);
    discardPeriodicTasks();

    expect(component.logs.length).toBe(0);
    expect(component.loadedFromArchive).toBeFalse();
  }));

  // ── 7. Log level filtering ────────────────────────────────────────────────────

  it('should toggle log level on/off', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    expect(component.isLevelSelected('INFO')).toBeTrue();
    component.toggleLevel('INFO');
    expect(component.isLevelSelected('INFO')).toBeFalse();

    component.toggleLevel('INFO');
    expect(component.isLevelSelected('INFO')).toBeTrue();
  }));

  it('should select all levels when selectAllLevels is called', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.selectedLevels = new Set(['INFO']);
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    jobLogServiceSpy.getLogsForJob.calls.reset();
    component.selectAllLevels();
    tick(200);
    discardPeriodicTasks();

    expect(component.selectedLevels.size).toBe(5);
    expect(jobLogServiceSpy.getLogsForJob).toHaveBeenCalled();
  }));

  it('should clear all levels when clearAllLevels is called', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.clearAllLevels();
    tick(100);
    discardPeriodicTasks();

    expect(component.selectedLevels.size).toBe(0);
  }));

  it('should reset to page 0 when toggling log level', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.currentPage = 3;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.toggleLevel('DEBUG');
    tick(200);
    discardPeriodicTasks();

    expect(component.currentPage).toBe(0);
  }));

  // ── 8. Tail mode toggle ────────────────────────────────────────────────────────

  it('should enable tail mode and connect WebSocket on toggleTailMode from non-tail', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.tailMode = false;
    jobLogServiceSpy.tailLogs.and.returnValue(of(makeLogTailResponse([])));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.toggleTailMode();
    tick(200);
    discardPeriodicTasks();

    expect(component.tailMode).toBeTrue();
    expect(webSocketServiceSpy.connect).toHaveBeenCalled();
  }));

  it('should disable tail mode and call loadLogs on toggleTailMode from tail', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.tailMode = true;
    component['streamingActive'] = true;

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    jobLogServiceSpy.getLogsForJob.calls.reset();
    component.toggleTailMode();
    tick(200);
    discardPeriodicTasks();

    expect(component.tailMode).toBeFalse();
    expect(jobLogServiceSpy.getLogsForJob).toHaveBeenCalled();
  }));

  // ── 9. WebSocket streaming – ingest source ─────────────────────────────────────

  it('should subscribe to ingest task logs when logSource is ingest', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = true;
    component.logSource = 'ingest';
    jobLogServiceSpy.tailLogs.and.returnValue(of(makeLogTailResponse([])));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    expect(webSocketServiceSpy.subscribeToTaskLogs).toHaveBeenCalledWith('task-1');
  }));

  it('should subscribe to vector-population logs when logSource is vector-population', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = true;
    component.logSource = 'vector-population';
    jobLogServiceSpy.tailLogs.and.returnValue(of(makeLogTailResponse([])));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    expect(webSocketServiceSpy.subscribeToVectorPopulationLogs).toHaveBeenCalledWith('task-1');
  }));

  // ── 10. processBatchedLogs ─────────────────────────────────────────────────────

  it('should add log entries through processBatchedLogs', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = false;
    component['initialLoadComplete'] = true;
    component.selectedLevels = new Set(['INFO', 'WARN', 'ERROR']);

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    const batch = [
      makeIngestLogEntry({ sequenceNumber: 100, message: 'first' }),
      makeIngestLogEntry({ sequenceNumber: 101, message: 'second' })
    ];

    component['processBatchedLogs'](batch);

    expect(component.logs.length).toBeGreaterThan(0);
    expect(component['lastSequenceNumber']).toBe(101);
  }));

  it('should skip duplicate sequence numbers in processBatchedLogs', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component['lastSequenceNumber'] = 50;
    component.selectedLevels = new Set(['INFO', 'WARN', 'ERROR']);

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    const initialLen = component.logs.length;
    const batch = [makeIngestLogEntry({ sequenceNumber: 30 })]; // older than lastSequenceNumber
    component['processBatchedLogs'](batch);

    expect(component.logs.length).toBe(initialLen);
  }));

  it('should enforce circular buffer and trim to maxTailLogs', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.maxTailLogs = 3;
    component.selectedLevels = new Set(['INFO', 'WARN', 'ERROR']);

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    // Pre-fill logs
    component.logs = [
      makeJobLogEntry({ id: 1, sequenceNumber: 1, level: 'INFO' }),
      makeJobLogEntry({ id: 2, sequenceNumber: 2, level: 'INFO' }),
      makeJobLogEntry({ id: 3, sequenceNumber: 3, level: 'INFO' })
    ];
    component.levelCounts = { INFO: 3 };

    const batch = [makeIngestLogEntry({ sequenceNumber: 100, message: 'overflow' })];
    component['processBatchedLogs'](batch);

    expect(component.logs.length).toBe(3);
    expect(component.logs[2].message).toBe('overflow');
  }));

  it('should filter by level in processBatchedLogs', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.selectedLevels = new Set(['ERROR']); // only ERROR

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    const initialLen = component.logs.length;
    const batch = [makeIngestLogEntry({ sequenceNumber: 200, level: 'INFO' })]; // not in filter
    component['processBatchedLogs'](batch);

    expect(component.logs.length).toBe(initialLen); // not added
  }));

  it('should filter by search text in processBatchedLogs', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.selectedLevels = new Set(['INFO', 'WARN', 'ERROR']);
    component.searchText = 'important';

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    const initialLen = component.logs.length;
    const batch = [makeIngestLogEntry({ sequenceNumber: 300, message: 'irrelevant message' })];
    component['processBatchedLogs'](batch);

    expect(component.logs.length).toBe(initialLen);
  }));

  // ── 11. getLevelClass / getLevelIcon / formatTimestamp ─────────────────────────

  it('should delegate getLevelClass to jobLogService', () => {
    component.getLevelClass('WARN');
    expect(jobLogServiceSpy.getLevelClass).toHaveBeenCalledWith('WARN');
  });

  it('should delegate getLevelIcon to jobLogService', () => {
    component.getLevelIcon('ERROR');
    expect(jobLogServiceSpy.getLevelIcon).toHaveBeenCalledWith('ERROR');
  });

  it('should delegate formatTimestamp to jobLogService', () => {
    component.formatTimestamp('2025-01-01T10:00:00.000Z');
    expect(jobLogServiceSpy.formatTimestamp).toHaveBeenCalled();
  });

  // ── 12. getLevelCount ──────────────────────────────────────────────────────────

  it('should return 0 for levels with no count', () => {
    component.levelCounts = {};
    expect(component.getLevelCount('DEBUG')).toBe(0);
  });

  it('should return the correct count for a populated level', () => {
    component.levelCounts = { ERROR: 7 };
    expect(component.getLevelCount('ERROR')).toBe(7);
  });

  // ── 13. clearSearch ────────────────────────────────────────────────────────────

  it('should clear search text and reload logs', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.searchText = 'something';
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    jobLogServiceSpy.getLogsForJob.calls.reset();
    component.clearSearch();
    tick(200);
    discardPeriodicTasks();

    expect(component.searchText).toBe('');
    expect(component.currentPage).toBe(0);
    expect(jobLogServiceSpy.getLogsForJob).toHaveBeenCalled();
  }));

  // ── 14. auto-scroll ────────────────────────────────────────────────────────────

  it('should have autoScroll enabled by default', () => {
    expect(component.autoScroll).toBeTrue();
  });

  it('should not scroll when logContainer is absent', () => {
    // scrollToBottom should be a no-op when there is no DOM element
    expect(() => component.scrollToBottom()).not.toThrow();
  });

  it('should not scroll to top when logContainer is absent', () => {
    expect(() => component.scrollToTop()).not.toThrow();
  });

  // ── 15. downloadLogs ──────────────────────────────────────────────────────────

  it('should call jobLogService.downloadLogsAsFile on downloadLogs', () => {
    component.taskId = 'task-1';
    component.downloadLogs();
    expect(jobLogServiceSpy.downloadLogsAsFile).toHaveBeenCalledWith('task-1');
  });

  // ── 16. refreshLogs ────────────────────────────────────────────────────────────

  it('should call enableTailMode on refreshLogs when in tail mode', fakeAsync(() => {
    component.taskId = 'task-1';
    component.tailMode = true;
    jobLogServiceSpy.tailLogs.and.returnValue(of(makeLogTailResponse([])));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    jobLogServiceSpy.tailLogs.calls.reset();
    component.refreshLogs();
    tick(200);
    discardPeriodicTasks();

    expect(jobLogServiceSpy.tailLogs).toHaveBeenCalled();
  }));

  it('should call loadLogs on refreshLogs when not in tail mode', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.tailMode = false;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    jobLogServiceSpy.getLogsForJob.calls.reset();
    component.refreshLogs();
    tick(200);
    discardPeriodicTasks();

    expect(jobLogServiceSpy.getLogsForJob).toHaveBeenCalled();
  }));

  // ── 17. tailLogs (one-shot HTTP method) ───────────────────────────────────────

  it('should call tailLogs and update logs', fakeAsync(() => {
    component.taskId = 'task-1';
    const log = makeJobLogEntry({ sequenceNumber: 99 });
    jobLogServiceSpy.tailLogs.and.returnValue(of(makeLogTailResponse([log])));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.tailLogs();
    tick(200);
    discardPeriodicTasks();

    expect(component.logs).toContain(log);
    expect(component.loading).toBeFalse();
  }));

  it('should set error when tailLogs HTTP call fails', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    jobLogServiceSpy.tailLogs.and.returnValue(throwError(() => ({ message: 'network error' })));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    // Call the one-shot tailLogs method (not enableTailMode)
    component.tailLogs();
    tick(200);
    discardPeriodicTasks();

    expect(component.error).toContain('Failed to tail logs');
    expect(component.loading).toBeFalse();
  }));

  // ── 18. ngOnChanges – taskId change ──────────────────────────────────────────

  it('should reload for completed job when taskId changes', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = false;
    component['initialLoadComplete'] = true;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    jobLogServiceSpy.getLogCount.calls.reset();
    jobLogServiceSpy.getLogsForJob.calls.reset();

    component.taskId = 'task-2';
    component.ngOnChanges({
      taskId: {
        currentValue: 'task-2',
        previousValue: 'task-1',
        firstChange: false,
        isFirstChange: () => false
      }
    });
    tick(500);
    discardPeriodicTasks();

    expect(jobLogServiceSpy.getLogCount).toHaveBeenCalledWith('task-2');
  }));

  it('should switch to tail mode when isJobRunning changes to true', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = false;
    component['initialLoadComplete'] = true;
    jobLogServiceSpy.tailLogs.and.returnValue(of(makeLogTailResponse([])));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.isJobRunning = true;
    component.ngOnChanges({
      isJobRunning: {
        currentValue: true,
        previousValue: false,
        firstChange: false,
        isFirstChange: () => false
      }
    });
    tick(200);
    discardPeriodicTasks();

    expect(component.tailMode).toBeTrue();
  }));

  it('should stop streaming and reload when isJobRunning changes to false', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = true;
    component['streamingActive'] = true;
    component.tailMode = true;
    component['initialLoadComplete'] = true;
    jobLogServiceSpy.tailLogs.and.returnValue(of(makeLogTailResponse([])));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    jobLogServiceSpy.getLogCount.calls.reset();
    component.isJobRunning = false;
    component.ngOnChanges({
      isJobRunning: {
        currentValue: false,
        previousValue: true,
        firstChange: false,
        isFirstChange: () => false
      }
    });
    tick(500);
    discardPeriodicTasks();

    expect(component.tailMode).toBeFalse();
    expect(component.streamingActive).toBeFalse();
  }));

  // ── 19. goToPage / pageInput ───────────────────────────────────────────────────

  it('should navigate to the specified page via goToPage', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    jobLogServiceSpy.getLogCount.and.returnValue(of({ enabled: true, taskId: 'task-1', count: 500, levelCounts: {} }));
    jobLogServiceSpy.getLogsForJob.and.returnValue(of(makeJobLogsResponse({ totalCount: 500 })));

    fixture.detectChanges();
    tick(500);
    discardPeriodicTasks();

    // Force known state after initial load
    component.totalCount = 500;
    component.pageSize = 100;
    component.currentPage = 0;
    component.pageInput = 3;
    jobLogServiceSpy.getLogsForJob.calls.reset();

    component.goToPage();
    tick(200);
    discardPeriodicTasks();

    expect(component.currentPage).toBe(2); // 1-indexed to 0-indexed
    expect(jobLogServiceSpy.getLogsForJob).toHaveBeenCalled();
  }));

  it('should clamp pageInput to valid range in goToPage', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    jobLogServiceSpy.getLogCount.and.returnValue(of({ enabled: true, taskId: 'task-1', count: 200, levelCounts: {} }));
    jobLogServiceSpy.getLogsForJob.and.returnValue(of(makeJobLogsResponse({ totalCount: 200 })));

    fixture.detectChanges();
    tick(500);
    discardPeriodicTasks();

    component.totalCount = 200;
    component.pageSize = 100;
    component.currentPage = 0;
    component.pageInput = 999;
    jobLogServiceSpy.getLogsForJob.calls.reset();

    component.goToPage();
    tick(200);
    discardPeriodicTasks();

    expect(component.currentPage).toBe(1); // last page index for 200 logs / 100 pageSize
  }));

  // ── 20. ngOnDestroy ────────────────────────────────────────────────────────────

  it('should call unsubscribeFromTaskLogs on destroy when logSource is ingest', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = true;
    component.logSource = 'ingest';
    jobLogServiceSpy.tailLogs.and.returnValue(of(makeLogTailResponse([])));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.ngOnDestroy();

    expect(webSocketServiceSpy.unsubscribeFromTaskLogs).toHaveBeenCalledWith('task-1');
  }));

  it('should call unsubscribeFromVectorPopulationLogs on destroy when logSource is vector-population', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = true;
    component.logSource = 'vector-population';
    jobLogServiceSpy.tailLogs.and.returnValue(of(makeLogTailResponse([])));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.ngOnDestroy();

    expect(webSocketServiceSpy.unsubscribeFromVectorPopulationLogs).toHaveBeenCalledWith('task-1');
  }));

  it('should not throw on destroy without prior taskId', () => {
    component.taskId = '';
    fixture.detectChanges();
    expect(() => component.ngOnDestroy()).not.toThrow();
  });

  // ── 21. getLogCount error fallback ────────────────────────────────────────────

  it('should fall back to page 0 when getLogCount fails', fakeAsync(() => {
    component.taskId = 'task-1';
    component.isJobRunning = false;
    jobLogServiceSpy.getLogCount.and.returnValue(throwError(() => new Error('count error')));
    jobLogServiceSpy.getLogsForJob.and.returnValue(of(makeJobLogsResponse()));

    fixture.detectChanges();
    tick(500);
    discardPeriodicTasks();

    expect(component.currentPage).toBe(0);
    expect(jobLogServiceSpy.getLogsForJob).toHaveBeenCalled();
  }));

  // ── 22. trackByLogId ────────────────────────────────────────────────────────────

  it('should return log id from trackByLogId when id is present', () => {
    const log = makeJobLogEntry({ id: 42, sequenceNumber: 1 });
    expect(component.trackByLogId(0, log)).toBe(42);
  });

  it('should fall back to sequenceNumber in trackByLogId when id is missing', () => {
    const log = { ...makeJobLogEntry({ sequenceNumber: 99 }), id: undefined as any };
    expect(component.trackByLogId(0, log)).toBe(99);
  });

  it('should fall back to index in trackByLogId when both id and sequenceNumber are missing', () => {
    const log = { ...makeJobLogEntry(), id: undefined as any, sequenceNumber: undefined as any };
    expect(component.trackByLogId(5, log)).toBe(5);
  });

  // ── 23. loadLogs – skips in tail mode ──────────────────────────────────────────

  it('should not call getLogsForJob when tailMode is true', fakeAsync(() => {
    component.taskId = 'task-1';
    component['initialLoadComplete'] = true;
    component.tailMode = true;

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    jobLogServiceSpy.getLogsForJob.calls.reset();
    component.loadLogs();
    tick(100);
    discardPeriodicTasks();

    expect(jobLogServiceSpy.getLogsForJob).not.toHaveBeenCalled();
  }));

  // ── 24. loadLogs – empty taskId guard ─────────────────────────────────────────

  it('should clear logs and return early when loadLogs called with empty taskId', fakeAsync(() => {
    component.taskId = '';
    component['initialLoadComplete'] = true;
    component.logs = [makeJobLogEntry()];

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.loadLogs();
    tick(100);
    discardPeriodicTasks();

    expect(component.logs).toEqual([]);
    expect(jobLogServiceSpy.getLogsForJob).not.toHaveBeenCalled();
  }));
});
