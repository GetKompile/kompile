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

import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { IngestEventService } from './ingest-event.service';
import {
  IngestEvent,
  IngestEventType,
  IngestPhase,
  EventLogStatusResponse,
  EventLogSummary,
  RecentEventsResponse,
  ErrorEventsResponse,
  TaskEventsResponse,
  TaskIdsResponse,
  TaskEnvironmentResponse,
  CleanupResponse,
  DeleteTaskEventsResponse
} from '../models/api-models';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function apiRoot(): string {
  const { protocol, hostname, port } = window.location;
  return `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
}

function makeEvent(overrides: Partial<IngestEvent> = {}): IngestEvent {
  return {
    id: 1,
    taskId: 'task-001',
    fileName: 'test.pdf',
    eventType: 'PROGRESS',
    message: 'Processing…',
    timestamp: '2025-01-01T10:00:00Z',
    ...overrides
  };
}

function makeStatus(overrides: Partial<EventLogStatusResponse> = {}): EventLogStatusResponse {
  return { enabled: true, totalEvents: 10, ...overrides };
}

function makeSummary(overrides: Partial<EventLogSummary> = {}): EventLogSummary {
  return {
    lookbackHours: 24,
    totalTasks: 5,
    completed: 3,
    failed: 1,
    cancelled: 0,
    errorCount: 2,
    averageDurationMs: 1500,
    totalEventsInDb: 50,
    ...overrides
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('IngestEventService', () => {
  let service: IngestEventService;
  let httpMock: HttpTestingController;
  let eventsBase: string;
  let backendBase: string;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [IngestEventService]
    });

    service = TestBed.inject(IngestEventService);
    httpMock = TestBed.inject(HttpTestingController);
    eventsBase = `${apiRoot()}/ingest/events`;
    backendBase = apiRoot();
  });

  afterEach(() => {
    service.ngOnDestroy();
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. Creation & observables
  // ─────────────────────────────────────────────────────────────────────────────

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should expose observable streams', () => {
    expect(service.events$).toBeDefined();
    expect(service.summary$).toBeDefined();
    expect(service.status$).toBeDefined();
    expect(service.newEvent$).toBeDefined();
    expect(service.wsConnected$).toBeDefined();
    expect(service.activeRestarts$).toBeDefined();
  });

  it('should start with empty events', (done) => {
    service.events$.subscribe(events => {
      expect(events).toEqual([]);
      done();
    });
  });

  it('should start with null summary', (done) => {
    service.summary$.subscribe(s => {
      expect(s).toBeNull();
      done();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. getStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getStatus()', () => {
    it('should GET /ingest/events/status', () => {
      const statusResponse = makeStatus();

      service.getStatus().subscribe(status => {
        expect(status.enabled).toBeTrue();
        expect(status.totalEvents).toBe(10);
      });

      const req = httpMock.expectOne(`${eventsBase}/status`);
      expect(req.request.method).toBe('GET');
      req.flush(statusResponse);
    });

    it('should update statusSubject on success', () => {
      const statusResponse = makeStatus({ enabled: false });
      let emitted: any = null;

      service.status$.subscribe(s => { emitted = s; });
      service.getStatus().subscribe();
      httpMock.expectOne(`${eventsBase}/status`).flush(statusResponse);

      expect(emitted?.enabled).toBeFalse();
    });

    it('should propagate errors', () => {
      let errorCaught = false;

      service.getStatus().subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(`${eventsBase}/status`).flush(
        {}, { status: 500, statusText: 'Server Error' }
      );

      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. getSummary()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getSummary()', () => {
    it('should GET /ingest/events/summary with default hours=24', () => {
      const summary = makeSummary();

      service.getSummary().subscribe(s => {
        expect(s.totalTasks).toBe(5);
      });

      const req = httpMock.expectOne(r =>
        r.url === `${eventsBase}/summary` && r.params.get('hours') === '24'
      );
      expect(req.request.method).toBe('GET');
      req.flush(summary);
    });

    it('should pass custom hours param', () => {
      service.getSummary(48).subscribe();

      const req = httpMock.expectOne(r => r.params.get('hours') === '48');
      expect(req.request.params.get('hours')).toBe('48');
      req.flush(makeSummary({ lookbackHours: 48 }));
    });

    it('should update summarySubject', () => {
      const summary = makeSummary({ completed: 10 });
      let emitted: any = null;

      service.summary$.subscribe(s => { emitted = s; });
      service.getSummary().subscribe();
      httpMock.expectOne(r => r.url.endsWith('/summary')).flush(summary);

      expect(emitted?.completed).toBe(10);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. getRecentEvents()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getRecentEvents()', () => {
    it('should GET /ingest/events/recent with hours param', () => {
      const events = [makeEvent(), makeEvent({ id: 2, taskId: 'task-002' })];
      const response: RecentEventsResponse = { lookbackHours: 24, eventCount: 2, events };

      service.getRecentEvents(24).subscribe(r => {
        expect(r.events.length).toBe(2);
      });

      const req = httpMock.expectOne(r =>
        r.url === `${eventsBase}/recent` && r.params.get('hours') === '24'
      );
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });

    it('should update eventsSubject with received events', () => {
      const events = [makeEvent()];
      let emitted: IngestEvent[] = [];

      service.events$.subscribe(e => { emitted = e; });
      service.getRecentEvents().subscribe();
      httpMock.expectOne(r => r.url.endsWith('/recent')).flush({
        lookbackHours: 24, eventCount: 1, events
      });

      expect(emitted.length).toBe(1);
    });

    it('should propagate HTTP errors', () => {
      let errorCaught = false;
      service.getRecentEvents().subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/recent')).flush(
        {}, { status: 503, statusText: 'Service Unavailable' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. getErrorEvents()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getErrorEvents()', () => {
    it('should GET /ingest/events/errors with hours param', () => {
      const errors = [makeEvent({ eventType: 'ERROR' })];
      const response: ErrorEventsResponse = { lookbackHours: 24, errorCount: 1, errors };

      service.getErrorEvents(24).subscribe(r => {
        expect(r.errors[0].eventType).toBe('ERROR');
      });

      const req = httpMock.expectOne(r =>
        r.url === `${eventsBase}/errors` && r.params.get('hours') === '24'
      );
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. getEventsForTask()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getEventsForTask()', () => {
    it('should GET /ingest/events/task/:taskId', () => {
      const events = [makeEvent({ taskId: 'task-abc' })];
      const response: TaskEventsResponse = { taskId: 'task-abc', eventCount: 1, events };

      service.getEventsForTask('task-abc').subscribe(r => {
        expect(r.taskId).toBe('task-abc');
        expect(r.events.length).toBe(1);
      });

      const req = httpMock.expectOne(`${eventsBase}/task/task-abc`);
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });

    it('should URL-encode the taskId', () => {
      service.getEventsForTask('task with spaces').subscribe();

      const req = httpMock.expectOne(`${eventsBase}/task/task%20with%20spaces`);
      expect(req.request.url).toContain('task%20with%20spaces');
      req.flush({ taskId: 'task with spaces', eventCount: 0, events: [] });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. getLatestEvent()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getLatestEvent()', () => {
    it('should GET /ingest/events/task/:taskId/latest', () => {
      const event = makeEvent({ eventType: 'COMPLETED' });

      service.getLatestEvent('task-001').subscribe(e => {
        expect(e?.eventType).toBe('COMPLETED');
      });

      const req = httpMock.expectOne(`${eventsBase}/task/task-001/latest`);
      expect(req.request.method).toBe('GET');
      req.flush(event);
    });

    it('should return null on 404', () => {
      let result: IngestEvent | null | undefined = undefined;

      service.getLatestEvent('nonexistent').subscribe(e => { result = e; });
      httpMock.expectOne(`${eventsBase}/task/nonexistent/latest`).flush(
        {}, { status: 404, statusText: 'Not Found' }
      );

      expect(result).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. getTaskEnvironment()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getTaskEnvironment()', () => {
    it('should GET /ingest/events/task/:taskId/environment', () => {
      const envResponse: TaskEnvironmentResponse = {
        taskId: 'task-001',
        fileName: 'test.pdf',
        timestamp: '2025-01-01T10:00:00Z',
        environmentCaptured: true
      };

      service.getTaskEnvironment('task-001').subscribe(r => {
        expect(r.environmentCaptured).toBeTrue();
      });

      const req = httpMock.expectOne(`${eventsBase}/task/task-001/environment`);
      expect(req.request.method).toBe('GET');
      req.flush(envResponse);
    });

    it('should return a fallback response on 404', () => {
      let result: TaskEnvironmentResponse | undefined;

      service.getTaskEnvironment('missing-task').subscribe(r => { result = r; });
      httpMock.expectOne(`${eventsBase}/task/missing-task/environment`).flush(
        {}, { status: 404, statusText: 'Not Found' }
      );

      expect(result?.environmentCaptured).toBeFalse();
      expect(result?.message).toBe('Task not found');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. getEventsInRange()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getEventsInRange()', () => {
    it('should GET /ingest/events/range with start and end params', () => {
      const start = new Date('2025-01-01T00:00:00Z');
      const end = new Date('2025-01-02T00:00:00Z');

      service.getEventsInRange(start, end).subscribe();

      const req = httpMock.expectOne(r =>
        r.url === `${eventsBase}/range` &&
        r.params.get('start') === start.toISOString() &&
        r.params.get('end') === end.toISOString()
      );
      expect(req.request.method).toBe('GET');
      req.flush({ lookbackHours: 24, eventCount: 0, events: [] });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. getTaskIds()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getTaskIds()', () => {
    it('should GET /ingest/events/tasks with hours param', () => {
      const response: TaskIdsResponse = { lookbackHours: 24, taskCount: 3, tasks: ['t1', 't2', 't3'] };

      service.getTaskIds(24).subscribe(r => {
        expect(r.tasks.length).toBe(3);
      });

      const req = httpMock.expectOne(r =>
        r.url === `${eventsBase}/tasks` && r.params.get('hours') === '24'
      );
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. deleteTaskEvents()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('deleteTaskEvents()', () => {
    it('should DELETE /ingest/events/task/:taskId', () => {
      const response: DeleteTaskEventsResponse = { taskId: 'task-001', deleted: true };

      service.deleteTaskEvents('task-001').subscribe(r => {
        expect(r.deleted).toBeTrue();
      });

      // deleteTaskEvents calls refreshEvents which triggers getRecentEvents internally
      const deleteReq = httpMock.expectOne(`${eventsBase}/task/task-001`);
      expect(deleteReq.request.method).toBe('DELETE');
      deleteReq.flush(response);

      // Flush the refreshEvents call
      httpMock.match(r => r.url.endsWith('/recent'));
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. forceCleanup()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('forceCleanup()', () => {
    it('should POST to /ingest/events/cleanup with days param', () => {
      const response: CleanupResponse = { olderThanDays: 7, deletedCount: 42 };

      service.forceCleanup(7).subscribe(r => {
        expect(r.deletedCount).toBe(42);
      });

      const req = httpMock.expectOne(r =>
        r.url === `${eventsBase}/cleanup` && r.params.get('days') === '7'
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      req.flush(response);

      // Flush refresh side effects
      httpMock.match(r => r.url.endsWith('/recent') || r.url.endsWith('/summary'));
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 13. manualRestart()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('manualRestart()', () => {
    it('should POST to /vector-population/subprocess/:taskId/restart', () => {
      service.manualRestart('task-001').subscribe();

      const req = httpMock.expectOne(
        `${backendBase}/vector-population/subprocess/task-001/restart`
      );
      expect(req.request.method).toBe('POST');
      req.flush({});
    });

    it('should propagate errors', () => {
      let errorCaught = false;
      service.manualRestart('bad-task').subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.includes('/restart')).flush(
        {}, { status: 500, statusText: 'Server Error' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 14. getRestartStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getRestartStatus()', () => {
    it('should GET /vector-population/subprocess/:taskId/restart-status', () => {
      const statusResponse = {
        taskId: 'task-001',
        restartEnabled: true,
        currentAttempt: 1,
        maxAttempts: 3,
        restartScheduled: false
      };

      service.getRestartStatus('task-001').subscribe(r => {
        expect(r.restartEnabled).toBeTrue();
      });

      const req = httpMock.expectOne(
        `${backendBase}/vector-population/subprocess/task-001/restart-status`
      );
      expect(req.request.method).toBe('GET');
      req.flush(statusResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 15. disableAutoRestart()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('disableAutoRestart()', () => {
    it('should POST to /vector-population/subprocess/:taskId/disable-restart', () => {
      service.disableAutoRestart('task-001').subscribe();

      const req = httpMock.expectOne(
        `${backendBase}/vector-population/subprocess/task-001/disable-restart`
      );
      expect(req.request.method).toBe('POST');
      req.flush({});
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 16. getRestartConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getRestartConfig()', () => {
    it('should GET /vector-population/restart-config', () => {
      const config = {
        enabled: true,
        maxAttempts: 3,
        initialBackoffMs: 5000,
        backoffMultiplier: 2,
        heapIncreaseFactor: 1.5,
        systemRamSafetyMargin: 0.2
      };

      service.getRestartConfig().subscribe(r => {
        expect(r.enabled).toBeTrue();
        expect(r.maxAttempts).toBe(3);
      });

      const req = httpMock.expectOne(`${backendBase}/vector-population/restart-config`);
      expect(req.request.method).toBe('GET');
      req.flush(config);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 17. setRestartEnabled()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('setRestartEnabled()', () => {
    it('should POST to /vector-population/restart-config/enabled?enabled=true', () => {
      service.setRestartEnabled(true).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.includes('/restart-config/enabled') && r.url.includes('enabled=true')
      );
      expect(req.request.method).toBe('POST');
      req.flush({});
    });

    it('should POST with enabled=false', () => {
      service.setRestartEnabled(false).subscribe();

      const req = httpMock.expectOne(r => r.url.includes('enabled=false'));
      expect(req.request.method).toBe('POST');
      req.flush({});
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 18. setMaxRestartAttempts()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('setMaxRestartAttempts()', () => {
    it('should POST to /vector-population/restart-config/max-attempts?maxAttempts=5', () => {
      service.setMaxRestartAttempts(5).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.includes('/restart-config/max-attempts') && r.url.includes('maxAttempts=5')
      );
      expect(req.request.method).toBe('POST');
      req.flush({});
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 19. Utility methods
  // ─────────────────────────────────────────────────────────────────────────────

  describe('utility methods', () => {
    describe('getCurrentEvents()', () => {
      it('should return current cached events', () => {
        expect(service.getCurrentEvents()).toEqual([]);
      });
    });

    describe('getCurrentSummary()', () => {
      it('should return null initially', () => {
        expect(service.getCurrentSummary()).toBeNull();
      });
    });

    describe('filterEventsByType()', () => {
      it('should filter events by type', () => {
        const events = [
          makeEvent({ eventType: 'ERROR' }),
          makeEvent({ eventType: 'PROGRESS' }),
          makeEvent({ eventType: 'COMPLETED' })
        ];
        const filtered = service.filterEventsByType(events, ['ERROR', 'COMPLETED']);
        expect(filtered.length).toBe(2);
        expect(filtered.every(e => e.eventType === 'ERROR' || e.eventType === 'COMPLETED')).toBeTrue();
      });

      it('should return empty array when no types match', () => {
        const events = [makeEvent({ eventType: 'PROGRESS' })];
        expect(service.filterEventsByType(events, ['ERROR'])).toEqual([]);
      });
    });

    describe('filterEventsByPhase()', () => {
      it('should filter events by phase', () => {
        const events = [
          makeEvent({ phase: IngestPhase.EMBEDDING }),
          makeEvent({ phase: IngestPhase.INDEXING }),
          makeEvent({ phase: IngestPhase.COMPLETED })
        ];
        const filtered = service.filterEventsByPhase(events, [IngestPhase.EMBEDDING]);
        expect(filtered.length).toBe(1);
        expect(filtered[0].phase).toBe(IngestPhase.EMBEDDING);
      });

      it('should skip events without a phase', () => {
        const events = [makeEvent()]; // no phase
        expect(service.filterEventsByPhase(events, [IngestPhase.EMBEDDING])).toEqual([]);
      });
    });

    describe('groupEventsByTask()', () => {
      it('should group events by taskId', () => {
        const events = [
          makeEvent({ taskId: 'A', id: 1 }),
          makeEvent({ taskId: 'B', id: 2 }),
          makeEvent({ taskId: 'A', id: 3 })
        ];
        const grouped = service.groupEventsByTask(events);
        expect(grouped.size).toBe(2);
        expect(grouped.get('A')?.length).toBe(2);
        expect(grouped.get('B')?.length).toBe(1);
      });
    });

    describe('sortEventsByTimestamp()', () => {
      it('should sort descending by default', () => {
        const events = [
          makeEvent({ timestamp: '2025-01-01T08:00:00Z' }),
          makeEvent({ timestamp: '2025-01-01T10:00:00Z' }),
          makeEvent({ timestamp: '2025-01-01T09:00:00Z' })
        ];
        const sorted = service.sortEventsByTimestamp(events);
        expect(new Date(sorted[0].timestamp).getTime()).toBeGreaterThan(
          new Date(sorted[1].timestamp).getTime()
        );
      });

      it('should sort ascending when ascending=true', () => {
        const events = [
          makeEvent({ timestamp: '2025-01-01T10:00:00Z' }),
          makeEvent({ timestamp: '2025-01-01T08:00:00Z' })
        ];
        const sorted = service.sortEventsByTimestamp(events, true);
        expect(new Date(sorted[0].timestamp).getTime()).toBeLessThan(
          new Date(sorted[1].timestamp).getTime()
        );
      });
    });

    describe('formatDuration()', () => {
      it('should return "-" for undefined', () => {
        expect(service.formatDuration(undefined)).toBe('-');
      });

      it('should format ms under 1s', () => {
        expect(service.formatDuration(500)).toBe('500ms');
      });

      it('should format seconds', () => {
        expect(service.formatDuration(5000)).toBe('5.0s');
      });

      it('should format minutes', () => {
        expect(service.formatDuration(120000)).toBe('2.0m');
      });

      it('should format hours', () => {
        expect(service.formatDuration(7200000)).toBe('2.0h');
      });
    });

    describe('formatTimestamp()', () => {
      it('should return a localized string', () => {
        const result = service.formatTimestamp('2025-01-01T12:00:00Z');
        expect(typeof result).toBe('string');
        expect(result.length).toBeGreaterThan(0);
      });
    });

    describe('getRelativeTime()', () => {
      it('should return "Just now" for very recent timestamps', () => {
        const now = new Date().toISOString();
        expect(service.getRelativeTime(now)).toBe('Just now');
      });

      it('should return minutes ago for timestamps <1 hour old', () => {
        const past = new Date(Date.now() - 5 * 60000).toISOString();
        expect(service.getRelativeTime(past)).toContain('min ago');
      });

      it('should return hours ago for timestamps <1 day old', () => {
        const past = new Date(Date.now() - 3 * 3600000).toISOString();
        expect(service.getRelativeTime(past)).toContain('hours ago');
      });

      it('should return days ago for older timestamps', () => {
        const past = new Date(Date.now() - 3 * 86400000).toISOString();
        expect(service.getRelativeTime(past)).toContain('days ago');
      });
    });

    describe('isRestartEvent()', () => {
      const restartTypes: IngestEventType[] = [
        'RESTART_SCHEDULED', 'RESTART_ATTEMPTED', 'RESTART_SUCCEEDED',
        'RESTART_FAILED', 'MEMORY_ANALYSIS', 'HEAP_ADJUSTED',
        'THREADS_REDUCED', 'MANUAL_RESTART'
      ];

      restartTypes.forEach(type => {
        it(`should return true for ${type}`, () => {
          expect(service.isRestartEvent(type)).toBeTrue();
        });
      });

      it('should return false for non-restart event types', () => {
        expect(service.isRestartEvent('PROGRESS')).toBeFalse();
        expect(service.isRestartEvent('COMPLETED')).toBeFalse();
        expect(service.isRestartEvent('ERROR')).toBeFalse();
      });
    });

    describe('isMemoryEvent()', () => {
      it('should return true for MEMORY_ANALYSIS, HEAP_ADJUSTED, THREADS_REDUCED', () => {
        expect(service.isMemoryEvent('MEMORY_ANALYSIS')).toBeTrue();
        expect(service.isMemoryEvent('HEAP_ADJUSTED')).toBeTrue();
        expect(service.isMemoryEvent('THREADS_REDUCED')).toBeTrue();
      });

      it('should return false for other types', () => {
        expect(service.isMemoryEvent('RESTART_SCHEDULED')).toBeFalse();
        expect(service.isMemoryEvent('PROGRESS')).toBeFalse();
      });
    });

    describe('getActiveRestarts()', () => {
      it('should return empty map initially', () => {
        expect(service.getActiveRestarts().size).toBe(0);
      });
    });

    describe('isPollingActive()', () => {
      it('should return false before polling starts', () => {
        expect(service.isPollingActive()).toBeFalse();
      });
    });

    describe('isWebSocketConnected()', () => {
      it('should return false before WebSocket connects', () => {
        expect(service.isWebSocketConnected()).toBeFalse();
      });
    });
  });

  // ═══════════════════════ EDGE CASE TESTS ═══════════════════════

  describe('formatDuration() – edge cases', () => {
    it('should return "-" for 0 (falsy guard with !ms)', () => {
      // This service uses `if (!ms) return "-"` which catches 0
      expect(service.formatDuration(0)).toBe('-');
    });

    it('should return "-" for null', () => {
      expect(service.formatDuration(null as any)).toBe('-');
    });

    it('should return "-" for NaN (falsy)', () => {
      // NaN is falsy so !NaN === true
      // Actually NaN is truthy for `!ms` — NaN is falsy, so !NaN is true
      expect(service.formatDuration(NaN)).toBe('-');
    });

    it('should handle negative value', () => {
      // -500: !(-500) is false, -500 < 1000 → "-500ms"
      expect(service.formatDuration(-500)).toBe('-500ms');
    });

    it('should handle exact boundary 1000ms', () => {
      expect(service.formatDuration(1000)).toBe('1.0s');
    });

    it('should handle exact boundary 60000ms', () => {
      expect(service.formatDuration(60000)).toBe('1.0m');
    });

    it('should handle exact boundary 3600000ms', () => {
      expect(service.formatDuration(3600000)).toBe('1.0h');
    });
  });

  describe('formatTimestamp() – edge cases', () => {
    it('should return "Invalid Date" for empty string', () => {
      // new Date('').toLocaleString() → "Invalid Date" in most browsers
      const result = service.formatTimestamp('');
      expect(result).toBe('Invalid Date');
    });

    it('should return "Invalid Date" for garbage string', () => {
      const result = service.formatTimestamp('not-a-date');
      expect(result).toBe('Invalid Date');
    });
  });

  describe('getRelativeTime() – edge cases', () => {
    it('should return "Just now" for current timestamp', () => {
      const now = new Date().toISOString();
      expect(service.getRelativeTime(now)).toBe('Just now');
    });

    it('should return "Just now" for future timestamp', () => {
      // Future time → diff is negative → negative < 60000 → "Just now"
      const future = new Date(Date.now() + 100000).toISOString();
      expect(service.getRelativeTime(future)).toBe('Just now');
    });

    it('should return minutes for 2 minutes ago', () => {
      const twoMinAgo = new Date(Date.now() - 120000).toISOString();
      expect(service.getRelativeTime(twoMinAgo)).toBe('2 min ago');
    });

    it('should return hours for 3 hours ago', () => {
      const threeHoursAgo = new Date(Date.now() - 10800000).toISOString();
      expect(service.getRelativeTime(threeHoursAgo)).toBe('3 hours ago');
    });

    it('should return days for 2 days ago', () => {
      const twoDaysAgo = new Date(Date.now() - 172800000).toISOString();
      expect(service.getRelativeTime(twoDaysAgo)).toBe('2 days ago');
    });

    it('should handle exact boundary 60000ms → 1 min ago', () => {
      const oneMinAgo = new Date(Date.now() - 60001).toISOString();
      expect(service.getRelativeTime(oneMinAgo)).toBe('1 min ago');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 20. startPolling() / stopPolling()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('startPolling() / stopPolling()', () => {
    it('should set isPolling to true after startPolling', fakeAsync(() => {
      service.startPolling(10000, 24);
      expect(service.isPollingActive()).toBeTrue();

      // Flush the initial refreshEvents and refreshSummary calls
      httpMock.match(r => r.url.endsWith('/recent') || r.url.endsWith('/summary'));

      service.stopPolling();
      tick(0);
    }));

    it('should set isPolling to false after stopPolling', fakeAsync(() => {
      service.startPolling(10000, 24);
      httpMock.match(() => true);

      service.stopPolling();
      expect(service.isPollingActive()).toBeFalse();
      tick(0);
    }));

    it('should not start duplicate polling when already active', fakeAsync(() => {
      service.startPolling(10000, 24);
      httpMock.match(() => true);

      service.startPolling(10000, 24); // second call should be ignored
      expect(service.isPollingActive()).toBeTrue();

      service.stopPolling();
      tick(0);
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 21. refreshEvents() / refreshSummary()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('refreshEvents()', () => {
    it('should call getRecentEvents with default hours 24', () => {
      service.refreshEvents();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/recent') && r.params.get('hours') === '24'
      );
      expect(req.request.params.get('hours')).toBe('24');
      req.flush({ lookbackHours: 24, eventCount: 0, events: [] });
    });

    it('should pass custom hours', () => {
      service.refreshEvents(48);
      const req = httpMock.expectOne(r => r.params.get('hours') === '48');
      expect(req.request.params.get('hours')).toBe('48');
      req.flush({ lookbackHours: 48, eventCount: 0, events: [] });
    });
  });

  describe('refreshSummary()', () => {
    it('should call getSummary with default hours 24', () => {
      service.refreshSummary();
      const req = httpMock.expectOne(r =>
        r.url.endsWith('/summary') && r.params.get('hours') === '24'
      );
      expect(req.request.params.get('hours')).toBe('24');
      req.flush(makeSummary());
    });
  });
});
