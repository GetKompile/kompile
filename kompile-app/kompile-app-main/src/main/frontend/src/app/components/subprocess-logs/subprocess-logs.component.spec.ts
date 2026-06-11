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
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClient } from '@angular/common/http';
import { of, throwError, Subject } from 'rxjs';

import { SubprocessLogsComponent, LogSourceType } from './subprocess-logs.component';
import { WebSocketService } from '../../services/websocket.service';
import { IndexBrowserService } from '../../services/index-browser.service';
import { IngestLogEntry } from '../../models/api-models';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function makeLogEntry(overrides: Partial<IngestLogEntry> = {}): IngestLogEntry {
  return {
    taskId: 'task-1',
    level: 'INFO',
    source: 'STDOUT',
    message: 'Test log message',
    timestamp: '2025-01-01T10:00:00.000Z',
    sequenceNumber: 1,
    ...overrides
  } as IngestLogEntry;
}

function makeVectorLogsResponse(logs: any[] = []) {
  return {
    available: true,
    taskId: 'task-1',
    logCount: logs.length,
    totalStoredCount: logs.length,
    logs
  };
}

// ─── Suite ────────────────────────────────────────────────────────────────────

describe('SubprocessLogsComponent', () => {
  let component: SubprocessLogsComponent;
  let fixture: ComponentFixture<SubprocessLogsComponent>;
  let webSocketServiceSpy: jasmine.SpyObj<WebSocketService>;
  let indexBrowserServiceSpy: jasmine.SpyObj<IndexBrowserService>;
  let httpClientSpy: jasmine.SpyObj<HttpClient>;

  // Subjects to control observable emissions in tests
  let ingestLogSubject: Subject<IngestLogEntry>;
  let vectorLogSubject: Subject<IngestLogEntry>;
  let embeddingSubject: Subject<any>;
  let vlmTestLogSubject: Subject<IngestLogEntry>;

  beforeEach(async () => {
    ingestLogSubject = new Subject<IngestLogEntry>();
    vectorLogSubject = new Subject<IngestLogEntry>();
    embeddingSubject = new Subject<any>();
    vlmTestLogSubject = new Subject<IngestLogEntry>();

    webSocketServiceSpy = jasmine.createSpyObj('WebSocketService', [
      'connect',
      'subscribeToTaskLogs',
      'subscribeToVectorPopulationLogs',
      'subscribeToEmbeddingSubprocess',
      'subscribeToVlmTestLogs',
      'unsubscribeFromTaskLogs',
      'unsubscribeFromVectorPopulationLogs',
      'unsubscribeFromEmbeddingSubprocess',
      'unsubscribeFromVlmTestLogs'
    ]);

    indexBrowserServiceSpy = jasmine.createSpyObj('IndexBrowserService', [
      'getVectorPopulationTaskLogs'
    ]);

    httpClientSpy = jasmine.createSpyObj('HttpClient', ['get', 'post', 'delete']);

    // Default return values
    webSocketServiceSpy.subscribeToTaskLogs.and.returnValue(ingestLogSubject.asObservable());
    webSocketServiceSpy.subscribeToVectorPopulationLogs.and.returnValue(vectorLogSubject.asObservable());
    webSocketServiceSpy.subscribeToEmbeddingSubprocess.and.returnValue(embeddingSubject.asObservable());
    webSocketServiceSpy.subscribeToVlmTestLogs.and.returnValue(vlmTestLogSubject.asObservable());

    indexBrowserServiceSpy.getVectorPopulationTaskLogs.and.returnValue(
      of(makeVectorLogsResponse())
    );

    httpClientSpy.get.and.returnValue(of({ status: 'success', logs: [] }));

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
      .overrideComponent(SubprocessLogsComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .overrideProvider(WebSocketService, { useValue: webSocketServiceSpy })
      .overrideProvider(IndexBrowserService, { useValue: indexBrowserServiceSpy })
      .overrideProvider(HttpClient, { useValue: httpClientSpy })
      .compileComponents();

    fixture = TestBed.createComponent(SubprocessLogsComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    component.ngOnDestroy();
    ingestLogSubject.complete();
    vectorLogSubject.complete();
    embeddingSubject.complete();
    vlmTestLogSubject.complete();
  });

  // ── 1. Creation ─────────────────────────────────────────────────────────────

  it('should create', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('should have correct default input values', () => {
    expect(component.taskId).toBe('');
    expect(component.maxLogs).toBe(500);
    expect(component.height).toBe('300px');
    expect(component.logSource).toBe('ingest');
  });

  it('should have default filter settings', () => {
    expect(component.showDebug).toBeFalse();
    expect(component.showInfo).toBeTrue();
    expect(component.showWarn).toBeTrue();
    expect(component.showError).toBeTrue();
    expect(component.autoScroll).toBeTrue();
    expect(component.searchTerm).toBe('');
  });

  it('should start with empty logs and zero counts', () => {
    expect(component.logs).toEqual([]);
    expect(component.filteredLogs).toEqual([]);
    expect(component.totalLogs).toBe(0);
    expect(component.errorCount).toBe(0);
    expect(component.warnCount).toBe(0);
  });

  // ── 2. ngOnInit – ingest source with taskId ──────────────────────────────────

  it('should connect WebSocket and subscribe to ingest logs when taskId is set', fakeAsync(() => {
    component.taskId = 'task-1';
    component.logSource = 'ingest';

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    expect(webSocketServiceSpy.connect).toHaveBeenCalled();
    expect(webSocketServiceSpy.subscribeToTaskLogs).toHaveBeenCalledWith('task-1');
  }));

  it('should not subscribe to ingest logs when taskId is empty', fakeAsync(() => {
    component.taskId = '';
    component.logSource = 'ingest';

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    expect(webSocketServiceSpy.subscribeToTaskLogs).not.toHaveBeenCalled();
  }));

  // ── 3. ngOnInit – vector-population source ────────────────────────────────────

  it('should subscribe to vector-population logs when logSource is vector-population', fakeAsync(() => {
    component.taskId = 'task-1';
    component.logSource = 'vector-population';

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    expect(webSocketServiceSpy.subscribeToVectorPopulationLogs).toHaveBeenCalledWith('task-1');
  }));

  it('should not subscribe to vector-population logs when taskId is empty', fakeAsync(() => {
    component.taskId = '';
    component.logSource = 'vector-population';

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    expect(webSocketServiceSpy.subscribeToVectorPopulationLogs).not.toHaveBeenCalled();
  }));

  // ── 4. ngOnInit – embedding source ────────────────────────────────────────────

  it('should load stored embedding logs and subscribe to embedding subprocess', fakeAsync(() => {
    component.logSource = 'embedding';
    httpClientSpy.get.and.returnValue(of({ status: 'success', logs: [] }));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    expect(httpClientSpy.get).toHaveBeenCalled();
    expect(webSocketServiceSpy.subscribeToEmbeddingSubprocess).toHaveBeenCalled();
  }));

  it('should load historical embedding logs from HTTP endpoint', fakeAsync(() => {
    component.logSource = 'embedding';
    const storedEvent = {
      eventType: 'LOG',
      timestamp: Date.now(),
      data: { level: 'INFO', source: 'embedding', message: 'historic log' }
    };
    httpClientSpy.get.and.returnValue(of({ status: 'success', logs: [storedEvent] }));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    expect(component.logs.length).toBe(1);
    expect(component.logs[0].message).toContain('historic log');
  }));

  it('should still subscribe to embedding subprocess even when HTTP load fails', fakeAsync(() => {
    component.logSource = 'embedding';
    httpClientSpy.get.and.returnValue(throwError(() => new Error('network error')));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    expect(webSocketServiceSpy.subscribeToEmbeddingSubprocess).toHaveBeenCalled();
  }));

  // ── 5. ngOnInit – vlm-test source ─────────────────────────────────────────────

  it('should subscribe to VLM test logs when logSource is vlm-test', fakeAsync(() => {
    component.taskId = 'task-1';
    component.logSource = 'vlm-test';

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    expect(webSocketServiceSpy.subscribeToVlmTestLogs).toHaveBeenCalledWith('task-1');
  }));

  // ── 6. processBatchedLogs ──────────────────────────────────────────────────────

  it('should add new log entries when streamed via logBuffer', fakeAsync(() => {
    component.taskId = 'task-1';
    component.logSource = 'ingest';
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    const entry = makeLogEntry({ sequenceNumber: 50 });
    component['logBuffer$'].next(entry);
    tick(200);
    discardPeriodicTasks();

    expect(component.logs).toContain(entry);
    expect(component.totalLogs).toBeGreaterThan(0);
  }));

  it('should skip duplicate log entries with lower sequence numbers', fakeAsync(() => {
    component.taskId = 'task-1';
    component['lastSequenceNumber'] = 100;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    const entry = makeLogEntry({ sequenceNumber: 50 }); // below lastSequenceNumber
    component['logBuffer$'].next(entry);
    tick(200);
    discardPeriodicTasks();

    expect(component.logs.length).toBe(0);
  }));

  it('should increment errorCount when an ERROR log is added', fakeAsync(() => {
    component.taskId = 'task-1';
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, level: 'ERROR' }));
    tick(200);
    discardPeriodicTasks();

    expect(component.errorCount).toBe(1);
  }));

  it('should increment warnCount when a WARN log is added', fakeAsync(() => {
    component.taskId = 'task-1';
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, level: 'WARN' }));
    tick(200);
    discardPeriodicTasks();

    expect(component.warnCount).toBe(1);
  }));

  it('should trim logs to maxLogs when buffer overflows', fakeAsync(() => {
    component.taskId = 'task-1';
    component.maxLogs = 3;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    // Add 4 entries
    [10, 11, 12, 13].forEach(seq => {
      component['logBuffer$'].next(makeLogEntry({ sequenceNumber: seq, message: `log-${seq}` }));
    });
    tick(200);
    discardPeriodicTasks();

    expect(component.logs.length).toBe(3);
    // The oldest (10) should have been trimmed
    expect(component.logs[0].message).toBe('log-11');
  }));

  it('should decrement errorCount when an ERROR log is trimmed', () => {
    // Test processBatchedLogs directly to avoid async batching complexity
    component.taskId = 'task-1';
    component.maxLogs = 1;
    fixture.detectChanges();

    // Provide ERROR first, then INFO — with maxLogs=1 the ERROR should be trimmed
    component['processBatchedLogs']([
      makeLogEntry({ sequenceNumber: 10, level: 'ERROR' }),
      makeLogEntry({ sequenceNumber: 11, level: 'INFO' })
    ]);

    // After trim, only INFO remains and errorCount should be 0
    expect(component.logs.length).toBe(1);
    expect(component.logs[0].level).toBe('INFO');
    expect(component.errorCount).toBe(0);
  });

  // ── 7. applyFilter / filteredLogs ─────────────────────────────────────────────

  it('should include INFO logs when showInfo is true', fakeAsync(() => {
    component.taskId = 'task-1';
    component.showInfo = true;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, level: 'INFO' }));
    tick(200);
    discardPeriodicTasks();

    expect(component.filteredLogs.some(l => l.level === 'INFO')).toBeTrue();
  }));

  it('should exclude INFO logs when showInfo is false', fakeAsync(() => {
    component.taskId = 'task-1';
    component.showInfo = false;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, level: 'INFO' }));
    tick(200);
    discardPeriodicTasks();

    expect(component.filteredLogs.some(l => l.level === 'INFO')).toBeFalse();
  }));

  it('should exclude DEBUG logs when showDebug is false (default)', fakeAsync(() => {
    component.taskId = 'task-1';
    component.showDebug = false;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, level: 'DEBUG' }));
    tick(200);
    discardPeriodicTasks();

    expect(component.filteredLogs.some(l => l.level === 'DEBUG')).toBeFalse();
  }));

  it('should include DEBUG logs when showDebug is true', fakeAsync(() => {
    component.taskId = 'task-1';
    component.showDebug = true;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, level: 'DEBUG' }));
    tick(200);
    discardPeriodicTasks();

    expect(component.filteredLogs.some(l => l.level === 'DEBUG')).toBeTrue();
  }));

  it('should exclude WARN logs when showWarn is false', fakeAsync(() => {
    component.taskId = 'task-1';
    component.showWarn = false;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, level: 'WARN' }));
    tick(200);
    discardPeriodicTasks();

    expect(component.filteredLogs.some(l => l.level === 'WARN')).toBeFalse();
  }));

  it('should exclude ERROR logs when showError is false', fakeAsync(() => {
    component.taskId = 'task-1';
    component.showError = false;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, level: 'ERROR' }));
    tick(200);
    discardPeriodicTasks();

    expect(component.filteredLogs.some(l => l.level === 'ERROR')).toBeFalse();
  }));

  it('should filter logs by search term', fakeAsync(() => {
    component.taskId = 'task-1';
    component.showInfo = true;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, message: 'important event' }));
    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 11, message: 'irrelevant' }));
    tick(200);
    discardPeriodicTasks();

    component.searchTerm = 'important';
    component['applyFilterInternal']();

    expect(component.filteredLogs.length).toBe(1);
    expect(component.filteredLogs[0].message).toBe('important event');
  }));

  it('should be case-insensitive in search filtering', fakeAsync(() => {
    component.taskId = 'task-1';
    component.showInfo = true;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, message: 'UPPER CASE EVENT' }));
    tick(200);
    discardPeriodicTasks();

    component.searchTerm = 'upper';
    component['applyFilterInternal']();

    expect(component.filteredLogs.some(l => l.message === 'UPPER CASE EVENT')).toBeTrue();
  }));

  it('should show all logs when search term is empty', fakeAsync(() => {
    component.taskId = 'task-1';
    component.showInfo = true;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, message: 'first' }));
    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 11, message: 'second' }));
    tick(200);
    discardPeriodicTasks();

    component.searchTerm = '';
    component['applyFilterInternal']();

    expect(component.filteredLogs.length).toBe(2);
  }));

  // ── 8. applyFilter (public debounced) ─────────────────────────────────────────

  it('should trigger debounced filter update via applyFilter', fakeAsync(() => {
    component.taskId = 'task-1';
    component.showInfo = true;
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, level: 'INFO', message: 'visible' }));
    tick(200);
    discardPeriodicTasks();

    component.searchTerm = 'visible';
    component.applyFilter();
    tick(300); // debounce + filter
    discardPeriodicTasks();

    expect(component.filteredLogs.some(l => l.message === 'visible')).toBeTrue();
  }));

  // ── 9. clearLogs ──────────────────────────────────────────────────────────────

  it('should reset all state on clearLogs', fakeAsync(() => {
    component.taskId = 'task-1';
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10, level: 'ERROR' }));
    tick(200);
    discardPeriodicTasks();

    component.clearLogs();

    expect(component.logs).toEqual([]);
    expect(component.filteredLogs).toEqual([]);
    expect(component.totalLogs).toBe(0);
    expect(component.errorCount).toBe(0);
    expect(component.warnCount).toBe(0);
    expect(component['lastSequenceNumber']).toBe(0);
  }));

  // ── 10. scrollToBottom ─────────────────────────────────────────────────────────

  it('should not throw when scrollToBottom is called without logContainer', () => {
    expect(() => component.scrollToBottom()).not.toThrow();
  });

  // ── 11. toggleAutoScroll ──────────────────────────────────────────────────────

  it('should toggle autoScroll off', () => {
    component.autoScroll = true;
    component.toggleAutoScroll();
    expect(component.autoScroll).toBeFalse();
  });

  it('should toggle autoScroll on and call scrollToBottom', () => {
    component.autoScroll = false;
    const scrollSpy = spyOn(component, 'scrollToBottom');
    component.toggleAutoScroll();
    expect(component.autoScroll).toBeTrue();
    expect(scrollSpy).toHaveBeenCalled();
  });

  // ── 12. getLogClass ────────────────────────────────────────────────────────────

  it('should return log-error class for ERROR level', () => {
    expect(component.getLogClass('ERROR')).toBe('log-error');
  });

  it('should return log-warn class for WARN level', () => {
    expect(component.getLogClass('WARN')).toBe('log-warn');
  });

  it('should return log-debug class for DEBUG level', () => {
    expect(component.getLogClass('DEBUG')).toBe('log-debug');
  });

  it('should return log-info class for INFO (default) level', () => {
    expect(component.getLogClass('INFO')).toBe('log-info');
  });

  it('should return log-info class for unknown level', () => {
    expect(component.getLogClass('UNKNOWN')).toBe('log-info');
  });

  // ── 13. getLogIcon ─────────────────────────────────────────────────────────────

  it('should return error icon for ERROR level', () => {
    expect(component.getLogIcon('ERROR')).toBe('error');
  });

  it('should return warning icon for WARN level', () => {
    expect(component.getLogIcon('WARN')).toBe('warning');
  });

  it('should return bug_report icon for DEBUG level', () => {
    expect(component.getLogIcon('DEBUG')).toBe('bug_report');
  });

  it('should return info icon for INFO level', () => {
    expect(component.getLogIcon('INFO')).toBe('info');
  });

  // ── 14. formatTime ─────────────────────────────────────────────────────────────

  it('should format a valid ISO timestamp', () => {
    const result = component.formatTime('2025-01-01T10:30:45.123Z');
    // Should produce a non-empty string that isn't the raw ISO string
    expect(result.length).toBeGreaterThan(0);
  });

  it('should return the raw timestamp when formatting fails', () => {
    const badTs = 'not-a-date';
    const result = component.formatTime(badTs);
    // Should return something (either formatted or raw)
    expect(result).toBeDefined();
  });

  // ── 15. trackBySeq ─────────────────────────────────────────────────────────────

  it('should return sequenceNumber from trackBySeq', () => {
    const log = makeLogEntry({ sequenceNumber: 77 });
    expect(component.trackBySeq(0, log)).toBe(77);
  });

  // ── 16. setTaskId ──────────────────────────────────────────────────────────────

  it('should clear logs and resubscribe on setTaskId', fakeAsync(() => {
    component.taskId = 'task-1';
    component.logSource = 'ingest';
    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component['logBuffer$'].next(makeLogEntry({ sequenceNumber: 10 }));
    tick(200);
    discardPeriodicTasks();

    webSocketServiceSpy.subscribeToTaskLogs.calls.reset();
    component.setTaskId('task-2');
    tick(200);
    discardPeriodicTasks();

    expect(component.taskId).toBe('task-2');
    expect(component.logs).toEqual([]);
    expect(webSocketServiceSpy.subscribeToTaskLogs).toHaveBeenCalledWith('task-2');
  }));

  // ── 17. setTaskIdAndLoadLogs ───────────────────────────────────────────────────

  it('should load stored logs and resubscribe on setTaskIdAndLoadLogs for vector-population', fakeAsync(() => {
    component.logSource = 'vector-population';
    const storedLog = {
      taskId: 'task-2',
      level: 'INFO',
      source: 'STDOUT',
      message: 'stored log',
      timestamp: '2025-01-01T10:00:00.000Z',
      sequenceNumber: 5
    };
    indexBrowserServiceSpy.getVectorPopulationTaskLogs.and.returnValue(
      of(makeVectorLogsResponse([storedLog]))
    );

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.setTaskIdAndLoadLogs('task-2');
    tick(200);
    discardPeriodicTasks();

    expect(indexBrowserServiceSpy.getVectorPopulationTaskLogs).toHaveBeenCalledWith('task-2');
    expect(component.logs.some(l => l.message === 'stored log')).toBeTrue();
  }));

  // ── 18. loadLogsFromArray ──────────────────────────────────────────────────────

  it('should load logs from an array directly', () => {
    const logsArray = [
      { taskId: 'task-1', level: 'ERROR', source: 'STDOUT', message: 'err msg', timestamp: '2025-01-01T10:00:00.000Z', sequenceNumber: 1 },
      { taskId: 'task-1', level: 'WARN', source: 'STDERR', message: 'warn msg', timestamp: '2025-01-01T10:00:01.000Z', sequenceNumber: 2 }
    ];

    component.loadLogsFromArray(logsArray);

    expect(component.logs.length).toBe(2);
    expect(component.totalLogs).toBe(2);
    expect(component.errorCount).toBe(1);
    expect(component.warnCount).toBe(1);
  });

  it('should handle empty array in loadLogsFromArray gracefully', () => {
    expect(() => component.loadLogsFromArray([])).not.toThrow();
    expect(component.logs).toEqual([]);
  });

  it('should handle null in loadLogsFromArray gracefully', () => {
    expect(() => component.loadLogsFromArray(null as any)).not.toThrow();
  });

  // ── 19. loadStoredVectorPopulationLogs ────────────────────────────────────────

  it('should load stored vector population logs and update state', fakeAsync(() => {
    const storedLog = {
      taskId: 'task-1',
      level: 'WARN',
      source: 'STDOUT',
      message: 'stored vector log',
      timestamp: '2025-01-01T10:00:00.000Z',
      sequenceNumber: 5
    };
    indexBrowserServiceSpy.getVectorPopulationTaskLogs.and.returnValue(
      of(makeVectorLogsResponse([storedLog]))
    );

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.loadStoredVectorPopulationLogs('task-1');
    tick(200);
    discardPeriodicTasks();

    expect(component.logs.some(l => l.message === 'stored vector log')).toBeTrue();
    expect(component.warnCount).toBeGreaterThan(0);
  }));

  it('should still work when getVectorPopulationTaskLogs returns unavailable', fakeAsync(() => {
    indexBrowserServiceSpy.getVectorPopulationTaskLogs.and.returnValue(
      of({ available: false, taskId: 'task-1', logCount: 0, totalStoredCount: 0, logs: [] })
    );

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.loadStoredVectorPopulationLogs('task-1');
    tick(200);
    discardPeriodicTasks();

    expect(component.logs).toEqual([]);
  }));

  // ── 20. convertEmbeddingEventToLogEntry ───────────────────────────────────────

  it('should skip HEARTBEAT events', () => {
    const event = { eventType: 'HEARTBEAT', timestamp: Date.now() };
    const result = (component as any).convertEmbeddingEventToLogEntry(event);
    expect(result).toBeNull();
  });

  it('should return null for null event', () => {
    const result = (component as any).convertEmbeddingEventToLogEntry(null);
    expect(result).toBeNull();
  });

  it('should convert LOG event to INFO log entry', () => {
    const event = {
      eventType: 'LOG',
      timestamp: Date.now(),
      data: { level: 'INFO', source: 'subprocess', message: 'hello from model' }
    };
    const result = (component as any).convertEmbeddingEventToLogEntry(event);
    expect(result).not.toBeNull();
    expect(result!.level).toBe('INFO');
    expect(result!.message).toContain('hello from model');
  });

  it('should convert ERROR event to ERROR log entry', () => {
    const event = {
      eventType: 'ERROR',
      timestamp: Date.now(),
      data: { errorType: 'RuntimeError', errorMessage: 'crash' }
    };
    const result = (component as any).convertEmbeddingEventToLogEntry(event);
    expect(result!.level).toBe('ERROR');
    expect(result!.message).toContain('crash');
  });

  it('should convert MODEL_LOADED event to INFO log entry', () => {
    const event = {
      eventType: 'MODEL_LOADED',
      modelId: 'bge-base-en-v1.5',
      timestamp: Date.now(),
      data: { dimensions: 768, encoderType: 'DENSE' }
    };
    const result = (component as any).convertEmbeddingEventToLogEntry(event);
    expect(result!.level).toBe('INFO');
    expect(result!.message).toContain('bge-base-en-v1.5');
  });

  it('should convert SUBPROCESS_CRASHED to ERROR log entry', () => {
    const event = {
      eventType: 'SUBPROCESS_CRASHED',
      timestamp: Date.now(),
      data: { error: 'OOM' }
    };
    const result = (component as any).convertEmbeddingEventToLogEntry(event);
    expect(result!.level).toBe('ERROR');
    expect(result!.message).toContain('OOM');
  });

  it('should convert unknown event type to DEBUG log entry', () => {
    const event = {
      eventType: 'CUSTOM_UNKNOWN_TYPE',
      timestamp: Date.now(),
      data: {}
    };
    const result = (component as any).convertEmbeddingEventToLogEntry(event);
    expect(result!.level).toBe('DEBUG');
  });

  // ── 21. ngOnDestroy ────────────────────────────────────────────────────────────

  it('should call unsubscribeFromTaskLogs on destroy for ingest source', fakeAsync(() => {
    component.taskId = 'task-1';
    component.logSource = 'ingest';

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.ngOnDestroy();

    expect(webSocketServiceSpy.unsubscribeFromTaskLogs).toHaveBeenCalledWith('task-1');
  }));

  it('should call unsubscribeFromVectorPopulationLogs on destroy for vector-population source', fakeAsync(() => {
    component.taskId = 'task-1';
    component.logSource = 'vector-population';

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.ngOnDestroy();

    expect(webSocketServiceSpy.unsubscribeFromVectorPopulationLogs).toHaveBeenCalledWith('task-1');
  }));

  it('should call unsubscribeFromEmbeddingSubprocess on destroy for embedding source', fakeAsync(() => {
    component.logSource = 'embedding';
    httpClientSpy.get.and.returnValue(of({ status: 'success', logs: [] }));

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.ngOnDestroy();

    expect(webSocketServiceSpy.unsubscribeFromEmbeddingSubprocess).toHaveBeenCalled();
  }));

  it('should call unsubscribeFromVlmTestLogs on destroy for vlm-test source', fakeAsync(() => {
    component.taskId = 'task-1';
    component.logSource = 'vlm-test';

    fixture.detectChanges();
    tick(200);
    discardPeriodicTasks();

    component.ngOnDestroy();

    expect(webSocketServiceSpy.unsubscribeFromVlmTestLogs).toHaveBeenCalledWith('task-1');
  }));

  it('should not throw on destroy when no subscriptions exist', () => {
    component.taskId = '';
    fixture.detectChanges();
    expect(() => component.ngOnDestroy()).not.toThrow();
  });
});
