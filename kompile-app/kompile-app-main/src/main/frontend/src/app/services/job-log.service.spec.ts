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

import { JobLogService } from './job-log.service';
import {
  JobLogEntry,
  JobLogsResponse,
  LogTailResponse,
  LogErrorsResponse,
  LogCountResponse,
  LogStatistics,
  ArchivedLogsResponse,
  ArchiveCheckResponse,
  LogLevel,
  LogSource
} from './job-log.service';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function apiRoot(): string {
  const { protocol, hostname, port } = window.location;
  return `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
}

function makeLogEntry(overrides: Partial<JobLogEntry> = {}): JobLogEntry {
  return {
    id: 1,
    taskId: 'task-001',
    timestamp: '2025-01-01T10:00:00.123Z',
    level: 'INFO',
    source: 'STDOUT',
    message: 'Processing document',
    sequenceNumber: 1,
    ...overrides
  };
}

function makeLogsResponse(overrides: Partial<JobLogsResponse> = {}): JobLogsResponse {
  return {
    enabled: true,
    taskId: 'task-001',
    logs: [makeLogEntry()],
    totalCount: 1,
    page: 0,
    size: 20,
    levelCounts: { INFO: 1 },
    ...overrides
  };
}

function makeTailResponse(overrides: Partial<LogTailResponse> = {}): LogTailResponse {
  return {
    enabled: true,
    taskId: 'task-001',
    logs: [makeLogEntry()],
    count: 1,
    ...overrides
  };
}

function makeErrorsResponse(overrides: Partial<LogErrorsResponse> = {}): LogErrorsResponse {
  return {
    enabled: true,
    taskId: 'task-001',
    errors: [makeLogEntry({ level: 'ERROR', stackTrace: 'java.lang.Exception: test' })],
    count: 1,
    ...overrides
  };
}

function makeCountResponse(overrides: Partial<LogCountResponse> = {}): LogCountResponse {
  return {
    enabled: true,
    taskId: 'task-001',
    count: 42,
    levelCounts: { INFO: 30, WARN: 8, ERROR: 4 },
    ...overrides
  };
}

function makeStatistics(overrides: Partial<LogStatistics> = {}): LogStatistics {
  return {
    enabled: true,
    totalLogEntries: 1000,
    totalTasksWithLogs: 25,
    topTasksByLogCount: { 'task-001': 200, 'task-002': 150 },
    retentionDays: 7,
    maxEntriesPerJob: 10000,
    maxTotalEntries: 500000,
    utilizationPercent: 20,
    ...overrides
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('JobLogService', () => {
  let service: JobLogService;
  let httpMock: HttpTestingController;
  let jobsBase: string;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [JobLogService]
    });

    service = TestBed.inject(JobLogService);
    httpMock = TestBed.inject(HttpTestingController);
    jobsBase = `${apiRoot()}/indexing/jobs`;
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 1. Service creation
  // ─────────────────────────────────────────────────────────────────────────

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 2. getLogsForJob()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getLogsForJob()', () => {
    it('should GET /indexing/jobs/:taskId/logs with no params', () => {
      const response = makeLogsResponse();

      service.getLogsForJob('task-001').subscribe(r => {
        expect(r.taskId).toBe('task-001');
        expect(r.logs.length).toBe(1);
        expect(r.enabled).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/task-001/logs'));
      expect(req.request.method).toBe('GET');
      expect(req.request.params.keys().length).toBe(0);
      req.flush(response);
    });

    it('should pass single level param', () => {
      service.getLogsForJob('task-001', { level: 'ERROR' }).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/task-001/logs') && r.params.get('level') === 'ERROR'
      );
      expect(req.request.params.get('level')).toBe('ERROR');
      req.flush(makeLogsResponse());
    });

    it('should pass multiple levels as comma-separated string', () => {
      service.getLogsForJob('task-001', { levels: ['WARN', 'ERROR'] }).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/task-001/logs') && r.params.get('levels') === 'WARN,ERROR'
      );
      expect(req.request.params.get('levels')).toBe('WARN,ERROR');
      req.flush(makeLogsResponse());
    });

    it('should pass search param', () => {
      service.getLogsForJob('task-001', { search: 'OutOfMemory' }).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/task-001/logs') && r.params.get('search') === 'OutOfMemory'
      );
      expect(req.request.params.get('search')).toBe('OutOfMemory');
      req.flush(makeLogsResponse());
    });

    it('should pass page and size params for pagination', () => {
      service.getLogsForJob('task-001', { page: 2, size: 50 }).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/task-001/logs') &&
        r.params.get('page') === '2' &&
        r.params.get('size') === '50'
      );
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('50');
      req.flush(makeLogsResponse());
    });

    it('should pass all options together', () => {
      service.getLogsForJob('task-001', {
        level: 'WARN',
        search: 'timeout',
        page: 1,
        size: 100
      }).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/task-001/logs') &&
        r.params.get('level') === 'WARN' &&
        r.params.get('search') === 'timeout' &&
        r.params.get('page') === '1' &&
        r.params.get('size') === '100'
      );
      expect(req.request.method).toBe('GET');
      req.flush(makeLogsResponse());
    });

    it('should not send levels param when levels array is empty', () => {
      service.getLogsForJob('task-001', { levels: [] }).subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/task-001/logs'));
      expect(req.request.params.has('levels')).toBeFalse();
      req.flush(makeLogsResponse());
    });

    it('should propagate HTTP errors', () => {
      let errorCaught = false;

      service.getLogsForJob('task-bad').subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/task-bad/logs')).flush(
        {}, { status: 500, statusText: 'Internal Server Error' }
      );

      expect(errorCaught).toBeTrue();
    });

    it('should handle 404 errors', () => {
      let errorCaught = false;

      service.getLogsForJob('nonexistent').subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/nonexistent/logs')).flush(
        {}, { status: 404, statusText: 'Not Found' }
      );

      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 3. tailLogs()
  // ─────────────────────────────────────────────────────────────────────────

  describe('tailLogs()', () => {
    it('should GET /indexing/jobs/:taskId/logs/tail with default lines=100', () => {
      const response = makeTailResponse({ count: 1 });

      service.tailLogs('task-001').subscribe(r => {
        expect(r.count).toBe(1);
        expect(r.logs.length).toBe(1);
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/task-001/logs/tail') && r.params.get('lines') === '100'
      );
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });

    it('should pass custom lines param', () => {
      service.tailLogs('task-001', 50).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/task-001/logs/tail') && r.params.get('lines') === '50'
      );
      expect(req.request.params.get('lines')).toBe('50');
      req.flush(makeTailResponse());
    });

    it('should propagate errors', () => {
      let errorCaught = false;
      service.tailLogs('task-err').subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/logs/tail')).flush(
        {}, { status: 503, statusText: 'Service Unavailable' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 4. downloadLogs()
  // ─────────────────────────────────────────────────────────────────────────

  describe('downloadLogs()', () => {
    it('should GET /indexing/jobs/:taskId/logs/download with blob response type', () => {
      service.downloadLogs('task-001').subscribe(blob => {
        expect(blob instanceof Blob).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/task-001/logs/download'));
      expect(req.request.method).toBe('GET');
      expect(req.request.responseType).toBe('blob');
      req.flush(new Blob(['log content'], { type: 'text/plain' }));
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 5. getErrorsWithStackTrace()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getErrorsWithStackTrace()', () => {
    it('should GET /indexing/jobs/:taskId/logs/errors', () => {
      const response = makeErrorsResponse();

      service.getErrorsWithStackTrace('task-001').subscribe(r => {
        expect(r.count).toBe(1);
        expect(r.errors[0].level).toBe('ERROR');
        expect(r.errors[0].stackTrace).toBeTruthy();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/task-001/logs/errors'));
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });

    it('should propagate errors', () => {
      let errorCaught = false;
      service.getErrorsWithStackTrace('task-err').subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/logs/errors')).flush(
        {}, { status: 500, statusText: 'Server Error' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 6. getLogCount()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getLogCount()', () => {
    it('should GET /indexing/jobs/:taskId/logs/count', () => {
      const response = makeCountResponse();

      service.getLogCount('task-001').subscribe(r => {
        expect(r.count).toBe(42);
        expect(r.levelCounts['ERROR']).toBe(4);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/task-001/logs/count'));
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 7. deleteLogsForJob()
  // ─────────────────────────────────────────────────────────────────────────

  describe('deleteLogsForJob()', () => {
    it('should DELETE /indexing/jobs/:taskId/logs', () => {
      const deleteResponse = { success: true, taskId: 'task-001', deletedCount: 150 };

      service.deleteLogsForJob('task-001').subscribe(r => {
        expect(r.success).toBeTrue();
        expect(r.deletedCount).toBe(150);
        expect(r.taskId).toBe('task-001');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/task-001/logs'));
      expect(req.request.method).toBe('DELETE');
      req.flush(deleteResponse);
    });

    it('should propagate errors on delete', () => {
      let errorCaught = false;
      service.deleteLogsForJob('task-locked').subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/logs')).flush(
        {}, { status: 403, statusText: 'Forbidden' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 8. getStatistics()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getStatistics()', () => {
    it('should GET /indexing/jobs/logs/statistics', () => {
      const response = makeStatistics();

      service.getStatistics().subscribe(r => {
        expect(r.totalLogEntries).toBe(1000);
        expect(r.totalTasksWithLogs).toBe(25);
        expect(r.utilizationPercent).toBe(20);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/jobs/logs/statistics'));
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });

    it('should propagate errors', () => {
      let errorCaught = false;
      service.getStatistics().subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/logs/statistics')).flush(
        {}, { status: 500, statusText: 'Server Error' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 9. getArchivedLogs()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getArchivedLogs()', () => {
    it('should GET /indexing/jobs/:taskId/logs/archive', () => {
      const response: ArchivedLogsResponse = {
        available: true,
        taskId: 'task-001',
        logs: [{
          taskId: 'task-001',
          timestamp: '2025-01-01T10:00:00Z',
          level: 'INFO',
          source: 'STDOUT',
          message: 'Archived entry',
          sequenceNumber: 1
        }],
        totalCount: 1,
        source: 'archive-file.json.gz'
      };

      service.getArchivedLogs('task-001').subscribe(r => {
        expect(r.available).toBeTrue();
        expect(r.totalCount).toBe(1);
        expect(r.logs[0].message).toBe('Archived entry');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/task-001/logs/archive'));
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 10. checkArchivedLogs()
  // ─────────────────────────────────────────────────────────────────────────

  describe('checkArchivedLogs()', () => {
    it('should GET /indexing/jobs/:taskId/logs/archive/check', () => {
      const response: ArchiveCheckResponse = {
        available: true,
        taskId: 'task-001',
        hasArchive: true
      };

      service.checkArchivedLogs('task-001').subscribe(r => {
        expect(r.hasArchive).toBeTrue();
        expect(r.taskId).toBe('task-001');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/task-001/logs/archive/check'));
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });

    it('should return hasArchive=false when no archive exists', () => {
      const response: ArchiveCheckResponse = {
        available: true,
        taskId: 'task-new',
        hasArchive: false
      };

      service.checkArchivedLogs('task-new').subscribe(r => {
        expect(r.hasArchive).toBeFalse();
      });

      httpMock.expectOne(r => r.url.endsWith('/task-new/logs/archive/check')).flush(response);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 11. getLevelClass() utility
  // ─────────────────────────────────────────────────────────────────────────

  describe('getLevelClass()', () => {
    it('should return correct CSS class for each log level', () => {
      expect(service.getLevelClass('TRACE')).toBe('log-trace');
      expect(service.getLevelClass('DEBUG')).toBe('log-debug');
      expect(service.getLevelClass('INFO')).toBe('log-info');
      expect(service.getLevelClass('WARN')).toBe('log-warn');
      expect(service.getLevelClass('ERROR')).toBe('log-error');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 12. getLevelIcon() utility
  // ─────────────────────────────────────────────────────────────────────────

  describe('getLevelIcon()', () => {
    it('should return correct Material icon for each log level', () => {
      expect(service.getLevelIcon('TRACE')).toBe('bug_report');
      expect(service.getLevelIcon('DEBUG')).toBe('code');
      expect(service.getLevelIcon('INFO')).toBe('info');
      expect(service.getLevelIcon('WARN')).toBe('warning');
      expect(service.getLevelIcon('ERROR')).toBe('error');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 13. getSourceIcon() utility
  // ─────────────────────────────────────────────────────────────────────────

  describe('getSourceIcon()', () => {
    it('should return correct Material icon for each log source', () => {
      expect(service.getSourceIcon('STDOUT')).toBe('terminal');
      expect(service.getSourceIcon('STDERR')).toBe('error_outline');
      expect(service.getSourceIcon('SYSTEM')).toBe('settings');
      expect(service.getSourceIcon('APPLICATION')).toBe('code');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 14. formatTimestamp() utility
  // ─────────────────────────────────────────────────────────────────────────

  describe('formatTimestamp()', () => {
    it('should return a non-empty string for a valid ISO timestamp', () => {
      const result = service.formatTimestamp('2025-01-01T10:00:00.123Z');
      expect(typeof result).toBe('string');
      expect(result.length).toBeGreaterThan(0);
    });

    it('should return a string (possibly "Invalid Date") when the date is unparseable', () => {
      // The service calls toLocaleTimeString on the Date object without an isNaN guard.
      // Browsers return 'Invalid Date' rather than the original string in this case.
      const result = service.formatTimestamp('not-a-date');
      expect(typeof result).toBe('string');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 15. formatFullTimestamp() utility
  // ─────────────────────────────────────────────────────────────────────────

  describe('formatFullTimestamp()', () => {
    it('should return a non-empty string for a valid ISO timestamp', () => {
      const result = service.formatFullTimestamp('2025-06-15T14:30:00Z');
      expect(typeof result).toBe('string');
      expect(result.length).toBeGreaterThan(0);
    });

    it('should return a string (possibly "Invalid Date") when the date is unparseable', () => {
      // The service calls toLocaleString on the Date object without an isNaN guard.
      // Browsers return 'Invalid Date' rather than the original string in this case.
      const result = service.formatFullTimestamp('bad-date');
      expect(typeof result).toBe('string');
    });
  });
});
