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

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { JobHistoryService } from './job-history.service';
import {
  IndexingJobHistory,
  JobStatistics,
  JobStatus,
  FailureReason,
  IngestPhase,
  PagedResponse,
  JobSummary,
  SubprocessEvent,
  SubprocessStatistics,
  SubprocessEventType,
  IngestEvent
} from './job-history.service';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function apiRoot(): string {
  const { protocol, hostname, port } = window.location;
  return `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
}

function makeJob(overrides: Partial<IndexingJobHistory> = {}): IndexingJobHistory {
  return {
    id: 1,
    taskId: 'task-001',
    fileName: 'document.pdf',
    status: 'COMPLETED',
    startTime: '2025-01-01T10:00:00Z',
    ...overrides
  };
}

function makePagedResponse(jobs: IndexingJobHistory[]): PagedResponse<IndexingJobHistory> {
  return {
    content: jobs,
    totalElements: jobs.length,
    totalPages: 1,
    size: 20,
    number: 0,
    first: true,
    last: true,
    empty: jobs.length === 0
  };
}

function makeStatistics(overrides: Partial<JobStatistics> = {}): JobStatistics {
  return {
    totalJobs: 100,
    completedJobs: 80,
    failedJobs: 10,
    cancelledJobs: 5,
    memoryKilledJobs: 2,
    activeJobs: 3,
    statusBreakdown: [{ status: 'COMPLETED', count: 80, avgDurationMs: 5000, totalDocumentsIndexed: 400 }],
    failureBreakdown: [{ reason: 'UNKNOWN', count: 10, avgDurationMs: 1000 }],
    embeddingModels: ['bge-base-en-v1.5'],
    loaders: ['PDF'],
    chunkers: ['recursive'],
    ...overrides
  };
}

function makeSubprocessEvent(overrides: Partial<SubprocessEvent> = {}): SubprocessEvent {
  return {
    id: 1,
    eventType: 'SUBPROCESS_STARTED',
    modelId: 'bge-base-en-v1.5',
    timestamp: '2025-01-01T10:00:00Z',
    ...overrides
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('JobHistoryService', () => {
  let service: JobHistoryService;
  let httpMock: HttpTestingController;
  let historyBase: string;
  let subprocessBase: string;
  let ingestBase: string;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [JobHistoryService]
    });

    service = TestBed.inject(JobHistoryService);
    httpMock = TestBed.inject(HttpTestingController);
    historyBase = `${apiRoot()}/indexing/history`;
    subprocessBase = `${apiRoot()}/subprocess-events`;
    ingestBase = `${apiRoot()}/ingest/events/task`;
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 1. Service creation & BehaviorSubject observables
  // ─────────────────────────────────────────────────────────────────────────

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should expose jobs$ observable', (done) => {
    service.jobs$.subscribe(jobs => {
      expect(Array.isArray(jobs)).toBeTrue();
      expect(jobs.length).toBe(0);
      done();
    });
  });

  it('should expose statistics$ observable starting with null', (done) => {
    service.statistics$.subscribe(stats => {
      expect(stats).toBeNull();
      done();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 2. getAllJobs()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getAllJobs()', () => {
    it('should GET /indexing/history with default page=0 and size=20', () => {
      const paged = makePagedResponse([makeJob(), makeJob({ id: 2, taskId: 'task-002' })]);

      service.getAllJobs().subscribe(r => {
        expect(r.content.length).toBe(2);
        expect(r.totalElements).toBe(2);
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/indexing/history') &&
        r.params.get('page') === '0' &&
        r.params.get('size') === '20'
      );
      expect(req.request.method).toBe('GET');
      req.flush(paged);
    });

    it('should pass custom page and size', () => {
      service.getAllJobs(3, 50).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/indexing/history') &&
        r.params.get('page') === '3' &&
        r.params.get('size') === '50'
      );
      expect(req.request.params.get('page')).toBe('3');
      expect(req.request.params.get('size')).toBe('50');
      req.flush(makePagedResponse([]));
    });

    it('should propagate errors', () => {
      let errorCaught = false;
      service.getAllJobs().subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/indexing/history')).flush(
        {}, { status: 500, statusText: 'Server Error' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 3. getJob()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getJob()', () => {
    it('should GET /indexing/history/:taskId', () => {
      const job = makeJob({ status: 'FAILED', failureReason: 'EMBEDDING_ERROR' });

      service.getJob('task-001').subscribe(r => {
        expect(r.taskId).toBe('task-001');
        expect(r.status).toBe('FAILED');
        expect(r.failureReason).toBe('EMBEDDING_ERROR');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/indexing/history/task-001'));
      expect(req.request.method).toBe('GET');
      req.flush(job);
    });

    it('should propagate 404 errors', () => {
      let errorCaught = false;
      service.getJob('missing-task').subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/history/missing-task')).flush(
        {}, { status: 404, statusText: 'Not Found' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 4. getJobsByStatus()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getJobsByStatus()', () => {
    it('should GET /indexing/history/status/:status with no hours param', () => {
      const jobs = [makeJob({ status: 'FAILED' })];

      service.getJobsByStatus('FAILED').subscribe(r => {
        expect(r.length).toBe(1);
        expect(r[0].status).toBe('FAILED');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/history/status/FAILED'));
      expect(req.request.method).toBe('GET');
      expect(req.request.params.has('hours')).toBeFalse();
      req.flush(jobs);
    });

    it('should pass optional hours param when provided', () => {
      service.getJobsByStatus('RUNNING', 48).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/status/RUNNING') && r.params.get('hours') === '48'
      );
      expect(req.request.params.get('hours')).toBe('48');
      req.flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 5. getJobsByStatusPaged()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getJobsByStatusPaged()', () => {
    it('should GET /indexing/history/status/:status/page with page and size', () => {
      service.getJobsByStatusPaged('COMPLETED', 1, 10).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/status/COMPLETED/page') &&
        r.params.get('page') === '1' &&
        r.params.get('size') === '10'
      );
      expect(req.request.method).toBe('GET');
      req.flush(makePagedResponse([]));
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 6. getRecentJobs()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getRecentJobs()', () => {
    it('should GET /indexing/history/recent with default hours=24', () => {
      const jobs = [makeJob(), makeJob({ id: 2, taskId: 'task-002' })];

      service.getRecentJobs().subscribe(r => {
        expect(r.length).toBe(2);
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/recent') && r.params.get('hours') === '24'
      );
      expect(req.request.method).toBe('GET');
      req.flush(jobs);
    });

    it('should update jobs$ BehaviorSubject after response', () => {
      const jobs = [makeJob()];
      let emitted: IndexingJobHistory[] = [];

      service.jobs$.subscribe(j => { emitted = j; });
      service.getRecentJobs().subscribe();

      httpMock.expectOne(r => r.url.endsWith('/history/recent')).flush(jobs);

      expect(emitted.length).toBe(1);
      expect(emitted[0].taskId).toBe('task-001');
    });

    it('should pass custom hours param', () => {
      service.getRecentJobs(72).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/recent') && r.params.get('hours') === '72'
      );
      expect(req.request.params.get('hours')).toBe('72');
      req.flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 7. getRecentJobsPaged()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getRecentJobsPaged()', () => {
    it('should GET /indexing/history/recent/page with hours, page, size params', () => {
      service.getRecentJobsPaged(48, 2, 25).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/recent/page') &&
        r.params.get('hours') === '48' &&
        r.params.get('page') === '2' &&
        r.params.get('size') === '25'
      );
      expect(req.request.method).toBe('GET');
      req.flush(makePagedResponse([]));
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 8. getJobsInRange()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getJobsInRange()', () => {
    it('should GET /indexing/history/range with start and end params', () => {
      const start = '2025-01-01T00:00:00Z';
      const end = '2025-01-07T23:59:59Z';

      service.getJobsInRange(start, end).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/range') &&
        r.params.get('start') === start &&
        r.params.get('end') === end
      );
      expect(req.request.method).toBe('GET');
      req.flush([makeJob()]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 9. getFailedJobs()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getFailedJobs()', () => {
    it('should GET /indexing/history/failed', () => {
      const jobs = [makeJob({ status: 'FAILED', failureReason: 'IO_ERROR' })];

      service.getFailedJobs().subscribe(r => {
        expect(r[0].failureReason).toBe('IO_ERROR');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/history/failed'));
      expect(req.request.method).toBe('GET');
      req.flush(jobs);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 10. getJobsByFailureReason()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getJobsByFailureReason()', () => {
    it('should GET /indexing/history/failed/:reason', () => {
      service.getJobsByFailureReason('OUT_OF_MEMORY').subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/history/failed/OUT_OF_MEMORY'));
      expect(req.request.method).toBe('GET');
      req.flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 11. getActiveJobs()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getActiveJobs()', () => {
    it('should GET /indexing/history/active', () => {
      const jobs = [makeJob({ status: 'RUNNING' }), makeJob({ id: 2, taskId: 'task-002', status: 'QUEUED' })];

      service.getActiveJobs().subscribe(r => {
        expect(r.length).toBe(2);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/history/active'));
      expect(req.request.method).toBe('GET');
      req.flush(jobs);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 12. getLatestJobs()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getLatestJobs()', () => {
    it('should GET /indexing/history/latest with default limit=10', () => {
      service.getLatestJobs().subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/latest') && r.params.get('limit') === '10'
      );
      expect(req.request.method).toBe('GET');
      req.flush([makeJob()]);
    });

    it('should pass custom limit', () => {
      service.getLatestJobs(5).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/latest') && r.params.get('limit') === '5'
      );
      expect(req.request.params.get('limit')).toBe('5');
      req.flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 13. searchByFileName()
  // ─────────────────────────────────────────────────────────────────────────

  describe('searchByFileName()', () => {
    it('should GET /indexing/history/search with fileName param', () => {
      const jobs = [makeJob({ fileName: 'report.pdf' })];

      service.searchByFileName('report').subscribe(r => {
        expect(r[0].fileName).toBe('report.pdf');
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/search') && r.params.get('fileName') === 'report'
      );
      expect(req.request.method).toBe('GET');
      req.flush(jobs);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 14. getHighMemoryJobs() and getLongRunningJobs()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getHighMemoryJobs()', () => {
    it('should GET /indexing/history/high-memory with default threshold=80', () => {
      service.getHighMemoryJobs().subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/high-memory') && r.params.get('threshold') === '80'
      );
      expect(req.request.method).toBe('GET');
      req.flush([]);
    });

    it('should pass custom threshold', () => {
      service.getHighMemoryJobs(90).subscribe();

      const req = httpMock.expectOne(r => r.params.get('threshold') === '90');
      req.flush([]);
    });
  });

  describe('getLongRunningJobs()', () => {
    it('should GET /indexing/history/long-running with default thresholdMs=60000', () => {
      service.getLongRunningJobs().subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/long-running') && r.params.get('thresholdMs') === '60000'
      );
      expect(req.request.method).toBe('GET');
      req.flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 15. getStatistics()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getStatistics()', () => {
    it('should GET /indexing/history/statistics with default lastHours=24', () => {
      const stats = makeStatistics();

      service.getStatistics().subscribe(r => {
        expect(r.totalJobs).toBe(100);
        expect(r.completedJobs).toBe(80);
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/statistics') && r.params.get('lastHours') === '24'
      );
      expect(req.request.method).toBe('GET');
      req.flush(stats);
    });

    it('should update statistics$ BehaviorSubject after response', () => {
      const stats = makeStatistics({ totalJobs: 50 });
      let emitted: JobStatistics | null = null;

      service.statistics$.subscribe(s => { emitted = s; });
      service.getStatistics().subscribe();

      httpMock.expectOne(r => r.url.endsWith('/history/statistics')).flush(stats);

      expect(emitted).not.toBeNull();
      expect(emitted!.totalJobs).toBe(50);
    });

    it('should pass custom lastHours param', () => {
      service.getStatistics(48).subscribe();

      const req = httpMock.expectOne(r => r.params.get('lastHours') === '48');
      req.flush(makeStatistics());
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 16. getFailureRate()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getFailureRate()', () => {
    it('should GET /indexing/history/statistics/failure-rate', () => {
      const response = { failureRatePercent: 10.5, periodHours: 24 };

      service.getFailureRate().subscribe(r => {
        expect(r.failureRatePercent).toBe(10.5);
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/statistics/failure-rate') && r.params.get('lastHours') === '24'
      );
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 17. getTaskEvents()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getTaskEvents()', () => {
    it('should GET /ingest/events/task/:taskId', () => {
      const response = {
        taskId: 'task-001',
        eventCount: 2,
        events: [
          { taskId: 'task-001', timestamp: '2025-01-01T10:00:00Z', eventType: 'QUEUED', message: 'Queued' } as IngestEvent,
          { taskId: 'task-001', timestamp: '2025-01-01T10:01:00Z', eventType: 'STARTED', message: 'Started' } as IngestEvent
        ]
      };

      service.getTaskEvents('task-001').subscribe(r => {
        expect(r.taskId).toBe('task-001');
        expect(r.eventCount).toBe(2);
        expect(r.events.length).toBe(2);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/ingest/events/task/task-001'));
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 18. getJobSummary()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getJobSummary()', () => {
    it('should GET /indexing/history/:taskId/summary', () => {
      const summary: Partial<JobSummary> = {
        taskId: 'task-001',
        fileName: 'document.pdf',
        status: 'COMPLETED',
        startTime: '2025-01-01T10:00:00Z',
        parameters: { loaderUsed: 'PDF', chunkSize: 500 },
        results: { documentsLoaded: 5, chunksCreated: 20 },
        environment: { javaVersion: '17' },
        memoryPercent: { usageAtStart: 30 }
      };

      service.getJobSummary('task-001').subscribe(r => {
        expect(r.taskId).toBe('task-001');
        expect(r.status).toBe('COMPLETED');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/history/task-001/summary'));
      expect(req.request.method).toBe('GET');
      req.flush(summary);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 19. deleteJob()
  // ─────────────────────────────────────────────────────────────────────────

  describe('deleteJob()', () => {
    it('should DELETE /indexing/history/:taskId', () => {
      const deleteResponse = { deleted: true, taskId: 'task-001' };

      service.deleteJob('task-001').subscribe(r => {
        expect(r.deleted).toBeTrue();
        expect(r.taskId).toBe('task-001');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/history/task-001'));
      expect(req.request.method).toBe('DELETE');
      req.flush(deleteResponse);
    });

    it('should propagate errors on delete', () => {
      let errorCaught = false;
      service.deleteJob('task-locked').subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/history/task-locked')).flush(
        {}, { status: 409, statusText: 'Conflict' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 20. forceCleanup()
  // ─────────────────────────────────────────────────────────────────────────

  describe('forceCleanup()', () => {
    it('should POST /indexing/history/cleanup with default olderThanDays=30', () => {
      const cleanupResponse = { deleted: 42, olderThanDays: 30 };

      service.forceCleanup().subscribe(r => {
        expect(r.deleted).toBe(42);
        expect(r.olderThanDays).toBe(30);
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/history/cleanup') && r.params.get('olderThanDays') === '30'
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      req.flush(cleanupResponse);
    });

    it('should pass custom olderThanDays param', () => {
      service.forceCleanup(7).subscribe();

      const req = httpMock.expectOne(r => r.params.get('olderThanDays') === '7');
      expect(req.request.method).toBe('POST');
      req.flush({ deleted: 0, olderThanDays: 7 });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 21. Subprocess event methods
  // ─────────────────────────────────────────────────────────────────────────

  describe('getRecentSubprocessEvents()', () => {
    it('should GET /subprocess-events/recent with default hours=24', () => {
      const events = [makeSubprocessEvent()];

      service.getRecentSubprocessEvents().subscribe(r => {
        expect(r.length).toBe(1);
        expect(r[0].eventType).toBe('SUBPROCESS_STARTED');
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/subprocess-events/recent') && r.params.get('hours') === '24'
      );
      expect(req.request.method).toBe('GET');
      req.flush(events);
    });
  });

  describe('getLatestSubprocessEvents()', () => {
    it('should GET /subprocess-events/latest', () => {
      service.getLatestSubprocessEvents().subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/subprocess-events/latest'));
      expect(req.request.method).toBe('GET');
      req.flush([]);
    });
  });

  describe('getSubprocessRestartEvents()', () => {
    it('should GET /subprocess-events/restarts', () => {
      const events = [makeSubprocessEvent({ eventType: 'SUBPROCESS_RESTARTING' })];

      service.getSubprocessRestartEvents().subscribe(r => {
        expect(r[0].eventType).toBe('SUBPROCESS_RESTARTING');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/subprocess-events/restarts'));
      expect(req.request.method).toBe('GET');
      req.flush(events);
    });
  });

  describe('getSubprocessRestartEventsForModel()', () => {
    it('should GET /subprocess-events/restarts/:modelId with URL encoding', () => {
      service.getSubprocessRestartEventsForModel('bge-base-en-v1.5').subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/subprocess-events/restarts/bge-base-en-v1.5')
      );
      expect(req.request.method).toBe('GET');
      req.flush([]);
    });
  });

  describe('getSubprocessEventsForTask()', () => {
    it('should GET /subprocess-events/task/:taskId', () => {
      service.getSubprocessEventsForTask('task-001').subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/subprocess-events/task/task-001')
      );
      expect(req.request.method).toBe('GET');
      req.flush([]);
    });
  });

  describe('getSubprocessEventById()', () => {
    it('should GET /subprocess-events/:id', () => {
      const event = makeSubprocessEvent({ id: 42 });

      service.getSubprocessEventById(42).subscribe(r => {
        expect(r.id).toBe(42);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/subprocess-events/42'));
      expect(req.request.method).toBe('GET');
      req.flush(event);
    });
  });

  describe('getSubprocessStatistics()', () => {
    it('should GET /subprocess-events/statistics', () => {
      const stats: SubprocessStatistics = {
        available: true,
        totalCrashes: 3,
        successfulRestarts: 2,
        restartSuccessRate: 66.7
      };

      service.getSubprocessStatistics().subscribe(r => {
        expect(r.available).toBeTrue();
        expect(r.totalCrashes).toBe(3);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/subprocess-events/statistics'));
      expect(req.request.method).toBe('GET');
      req.flush(stats);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 22. Utility methods
  // ─────────────────────────────────────────────────────────────────────────

  describe('formatDuration()', () => {
    it('should return "-" for undefined', () => {
      expect(service.formatDuration(undefined)).toBe('-');
    });

    it('should format milliseconds under 1s', () => {
      expect(service.formatDuration(500)).toBe('500ms');
    });

    it('should format seconds between 1s and 60s', () => {
      expect(service.formatDuration(5000)).toContain('s');
    });

    it('should format minutes between 1m and 1h', () => {
      const result = service.formatDuration(120000);
      expect(result).toContain('m');
    });

    it('should format hours for durations >= 1h', () => {
      const result = service.formatDuration(7200000);
      expect(result).toContain('h');
    });
  });

  describe('formatBytes()', () => {
    it('should return "-" for undefined', () => {
      expect(service.formatBytes(undefined)).toBe('-');
    });

    it('should format bytes', () => {
      expect(service.formatBytes(512)).toContain('B');
    });

    it('should format kilobytes', () => {
      expect(service.formatBytes(2048)).toContain('KB');
    });

    it('should format megabytes', () => {
      expect(service.formatBytes(5 * 1024 * 1024)).toContain('MB');
    });

    it('should format gigabytes', () => {
      expect(service.formatBytes(2 * 1024 * 1024 * 1024)).toContain('GB');
    });
  });

  describe('formatTimestamp()', () => {
    it('should return "-" for null', () => {
      expect(service.formatTimestamp(null)).toBe('-');
    });

    it('should return "-" for undefined', () => {
      expect(service.formatTimestamp(undefined)).toBe('-');
    });

    it('should format ISO string', () => {
      const result = service.formatTimestamp('2025-01-01T10:00:00Z');
      expect(typeof result).toBe('string');
      expect(result).not.toBe('-');
    });

    it('should handle epoch milliseconds (13+ digits)', () => {
      const result = service.formatTimestamp(1735726800000);
      expect(result).not.toBe('-');
    });

    it('should handle epoch seconds (10 digits)', () => {
      const result = service.formatTimestamp(1735726800);
      expect(result).not.toBe('-');
    });

    it('should return "-" for invalid date string', () => {
      expect(service.formatTimestamp('not-a-date')).toBe('-');
    });
  });

  describe('getStatusClass()', () => {
    it('should return correct CSS class for each status', () => {
      expect(service.getStatusClass('COMPLETED')).toBe('status-success');
      expect(service.getStatusClass('RUNNING')).toBe('status-info');
      expect(service.getStatusClass('QUEUED')).toBe('status-info');
      expect(service.getStatusClass('FAILED')).toBe('status-error');
      expect(service.getStatusClass('MEMORY_KILLED')).toBe('status-error');
      expect(service.getStatusClass('CANCELLED')).toBe('status-warning');
    });
  });

  describe('getStatusIcon()', () => {
    it('should return correct icon for each status', () => {
      expect(service.getStatusIcon('COMPLETED')).toBe('check_circle');
      expect(service.getStatusIcon('RUNNING')).toBe('sync');
      expect(service.getStatusIcon('QUEUED')).toBe('hourglass_empty');
      expect(service.getStatusIcon('FAILED')).toBe('error');
      expect(service.getStatusIcon('MEMORY_KILLED')).toBe('memory');
      expect(service.getStatusIcon('CANCELLED')).toBe('cancel');
    });
  });

  describe('getPhaseIcon()', () => {
    it('should return "help" for undefined phase', () => {
      expect(service.getPhaseIcon(undefined)).toBe('help');
    });

    it('should return correct icon for each phase', () => {
      expect(service.getPhaseIcon('QUEUED')).toBe('hourglass_empty');
      expect(service.getPhaseIcon('LOADING')).toBe('upload_file');
      expect(service.getPhaseIcon('CONVERTING')).toBe('transform');
      expect(service.getPhaseIcon('CHUNKING')).toBe('content_cut');
      expect(service.getPhaseIcon('EMBEDDING')).toBe('psychology');
      expect(service.getPhaseIcon('INDEXING')).toBe('storage');
      expect(service.getPhaseIcon('COMPLETED')).toBe('check_circle');
      expect(service.getPhaseIcon('FAILED')).toBe('error');
    });
  });

  describe('getSubprocessEventIcon()', () => {
    it('should return correct icon for each subprocess event type', () => {
      expect(service.getSubprocessEventIcon('SUBPROCESS_STARTED')).toBe('play_arrow');
      expect(service.getSubprocessEventIcon('SUBPROCESS_STOPPED')).toBe('stop');
      expect(service.getSubprocessEventIcon('SUBPROCESS_CRASHED')).toBe('error');
      expect(service.getSubprocessEventIcon('SUBPROCESS_RESTARTING')).toBe('autorenew');
      expect(service.getSubprocessEventIcon('SUBPROCESS_RESTART_SUCCESS')).toBe('check_circle');
      expect(service.getSubprocessEventIcon('SUBPROCESS_RESTART_EXHAUSTED')).toBe('block');
      expect(service.getSubprocessEventIcon('MODEL_LOADED')).toBe('download_done');
      expect(service.getSubprocessEventIcon('MODEL_FAILED')).toBe('error_outline');
    });
  });

  describe('getSubprocessEventClass()', () => {
    it('should return status-success for success event types', () => {
      expect(service.getSubprocessEventClass('SUBPROCESS_STARTED')).toBe('status-success');
      expect(service.getSubprocessEventClass('SUBPROCESS_RESTART_SUCCESS')).toBe('status-success');
      expect(service.getSubprocessEventClass('MODEL_LOADED')).toBe('status-success');
    });

    it('should return status-info for RESTARTING', () => {
      expect(service.getSubprocessEventClass('SUBPROCESS_RESTARTING')).toBe('status-info');
    });

    it('should return status-warning for STOPPED', () => {
      expect(service.getSubprocessEventClass('SUBPROCESS_STOPPED')).toBe('status-warning');
    });

    it('should return status-error for crash/failure types', () => {
      expect(service.getSubprocessEventClass('SUBPROCESS_CRASHED')).toBe('status-error');
      expect(service.getSubprocessEventClass('SUBPROCESS_RESTART_EXHAUSTED')).toBe('status-error');
      expect(service.getSubprocessEventClass('MODEL_FAILED')).toBe('status-error');
    });
  });

  describe('formatSubprocessEventType()', () => {
    it('should convert snake_case to Title Case', () => {
      expect(service.formatSubprocessEventType('SUBPROCESS_STARTED')).toBe('Subprocess Started');
      expect(service.formatSubprocessEventType('MODEL_LOADED')).toBe('Model Loaded');
      expect(service.formatSubprocessEventType('SUBPROCESS_RESTART_SUCCESS')).toBe('Subprocess Restart Success');
    });
  });

  // ═══════════════════════ EDGE CASE TESTS ═══════════════════════

  describe('formatDuration() – edge cases', () => {
    it('should return "-" for null', () => {
      expect(service.formatDuration(null as any)).toBe('-');
    });

    it('should return "0ms" for zero', () => {
      expect(service.formatDuration(0)).toBe('0ms');
    });

    it('should return "NaNms" for NaN (no guard)', () => {
      // NaN < 1000 is false, NaN < 60000 is false, etc. — falls through to hours branch
      const result = service.formatDuration(NaN);
      expect(result).toContain('NaN');
    });

    it('should handle negative values', () => {
      // -500 < 1000 → "-500ms"
      expect(service.formatDuration(-500)).toBe('-500ms');
    });

    it('should handle exact boundary 1000ms → seconds', () => {
      expect(service.formatDuration(1000)).toBe('1.0s');
    });

    it('should handle exact boundary 60000ms → minutes', () => {
      expect(service.formatDuration(60000)).toBe('1m 0s');
    });

    it('should handle exact boundary 3600000ms → hours', () => {
      expect(service.formatDuration(3600000)).toBe('1h 0m');
    });
  });

  describe('formatBytes() – edge cases', () => {
    it('should return "-" for null', () => {
      expect(service.formatBytes(null as any)).toBe('-');
    });

    it('should return "0 B" for zero', () => {
      expect(service.formatBytes(0)).toBe('0 B');
    });

    it('should handle exact boundary 1024 → KB', () => {
      expect(service.formatBytes(1024)).toBe('1.0 KB');
    });

    it('should handle exact boundary 1048576 → MB', () => {
      expect(service.formatBytes(1048576)).toBe('1.0 MB');
    });

    it('should handle exact boundary 1073741824 → GB', () => {
      expect(service.formatBytes(1073741824)).toBe('1.0 GB');
    });

    it('should handle negative bytes', () => {
      // -100 < 1024 → "-100 B"
      expect(service.formatBytes(-100)).toBe('-100 B');
    });
  });

  describe('formatTimestamp() – edge cases', () => {
    it('should return "-" for empty string', () => {
      expect(service.formatTimestamp('')).toBe('-');
    });

    it('should return "-" for whitespace-only string', () => {
      // new Date("  ") → Invalid Date
      expect(service.formatTimestamp('  ')).toBe('-');
    });

    it('should handle numeric string "0"', () => {
      // "0" → Number("0") = 0, String(0) = "0" matches → recurse with 0
      // 0 is a number, timestamp !== 0 check passes, 0 < 10000000000 → epoch seconds → new Date(0)
      const result = service.formatTimestamp('0');
      expect(result).not.toBe('-');
    });

    it('should handle epoch 0 as number', () => {
      // 0 as number: !timestamp is true BUT timestamp === 0 guard lets it through
      const result = service.formatTimestamp(0);
      expect(result).not.toBe('-');
    });

    it('should return "-" for completely invalid string', () => {
      expect(service.formatTimestamp('not-a-date')).toBe('-');
    });

    it('should handle very large epoch ms', () => {
      // Year 3000 epoch ms
      const result = service.formatTimestamp(32503680000000);
      expect(result).not.toBe('-');
    });
  });

  describe('getStatusClass() – edge cases', () => {
    it('should return empty string for PAUSED status', () => {
      expect(service.getStatusClass('PAUSED' as any)).toBe('');
    });

    it('should return empty string for unknown status', () => {
      expect(service.getStatusClass('UNKNOWN_STATUS' as any)).toBe('');
    });
  });

  describe('getPhaseIcon() – edge cases', () => {
    it('should return "help" for unknown phase string', () => {
      expect(service.getPhaseIcon('UNKNOWN_PHASE' as any)).toBe('help');
    });

    it('should return "help" for empty string phase', () => {
      expect(service.getPhaseIcon('' as any)).toBe('help');
    });
  });

  describe('formatSubprocessEventType() – edge cases', () => {
    it('should handle single-word input', () => {
      expect(service.formatSubprocessEventType('STARTED' as any)).toBe('Started');
    });

    it('should handle empty string', () => {
      expect(service.formatSubprocessEventType('' as any)).toBe('');
    });

    it('should handle already-lowercase input', () => {
      expect(service.formatSubprocessEventType('subprocess_started' as any)).toBe('Subprocess Started');
    });
  });
});
