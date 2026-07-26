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

import { LogConfigService } from './log-config.service';
import {
  LogConfiguration,
  LogStatus,
  LogConfigResponse,
  LogConfigUpdateRequest,
  ArchiveListResponse,
  ArchiveCreateResponse,
  ArchiveInfo,
  CleanupResponse
} from './log-config.service';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function apiRoot(): string {
  const { protocol, hostname, port } = window.location;
  return `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
}

function makeConfig(overrides: Partial<LogConfiguration> = {}): LogConfiguration {
  return {
    enabled: true,
    retentionDays: 7,
    maxEntriesPerJob: 10000,
    maxTotalEntries: 500000,
    archiveEnabled: true,
    archivePath: '/var/log/kompile/archives',
    archiveOnCleanup: true,
    ...overrides
  };
}

function makeStatus(overrides: Partial<LogStatus> = {}): LogStatus {
  return {
    enabled: true,
    totalLogEntries: 12345,
    maxTotalEntries: 500000,
    utilizationPercent: 2.5,
    retentionDays: 7,
    activeSequenceTrackers: 3,
    ...overrides
  };
}

function makeConfigResponse(overrides: Partial<LogConfigResponse> = {}): LogConfigResponse {
  return {
    available: true,
    config: makeConfig(),
    status: makeStatus(),
    ...overrides
  };
}

function makeArchiveInfo(overrides: Partial<ArchiveInfo> = {}): ArchiveInfo {
  return {
    fileName: 'logs-2025-01-01.json.gz',
    filePath: '/var/log/kompile/archives/logs-2025-01-01.json.gz',
    sizeBytes: 102400,
    sizeFormatted: '100.0 KB',
    createdAt: '2025-01-01T23:59:59Z',
    ...overrides
  };
}

function makeArchiveListResponse(overrides: Partial<ArchiveListResponse> = {}): ArchiveListResponse {
  return {
    available: true,
    archiveEnabled: true,
    archivePath: '/var/log/kompile/archives',
    archiveOnCleanup: true,
    archives: [makeArchiveInfo()],
    ...overrides
  };
}

function makeCleanupResponse(overrides: Partial<CleanupResponse> = {}): CleanupResponse {
  return {
    success: true,
    deletedCount: 500,
    hoursRetained: 168,
    statusBefore: makeStatus({ totalLogEntries: 12000 }),
    statusAfter: makeStatus({ totalLogEntries: 11500 }),
    ...overrides
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('LogConfigService', () => {
  let service: LogConfigService;
  let httpMock: HttpTestingController;
  let configBase: string;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [LogConfigService]
    });

    service = TestBed.inject(LogConfigService);
    httpMock = TestBed.inject(HttpTestingController);
    configBase = `${apiRoot()}/config/logs`;
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
  // 2. getConfiguration()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getConfiguration()', () => {
    it('should GET /config/logs', () => {
      const response = makeConfigResponse();

      service.getConfiguration().subscribe(r => {
        expect(r.available).toBeTrue();
        expect(r.config.enabled).toBeTrue();
        expect(r.config.retentionDays).toBe(7);
        expect(r.status.totalLogEntries).toBe(12345);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/config/logs'));
      expect(req.request.method).toBe('GET');
      expect(req.request.params.keys().length).toBe(0);
      req.flush(response);
    });

    it('should return available=false when logging disabled', () => {
      const response = makeConfigResponse({
        available: false,
        config: makeConfig({ enabled: false }),
        message: 'Logging is disabled'
      });

      service.getConfiguration().subscribe(r => {
        expect(r.available).toBeFalse();
        expect(r.config.enabled).toBeFalse();
        expect(r.message).toBe('Logging is disabled');
      });

      httpMock.expectOne(r => r.url.endsWith('/config/logs')).flush(response);
    });

    it('should propagate HTTP errors', () => {
      let errorCaught = false;
      service.getConfiguration().subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/config/logs')).flush(
        {}, { status: 500, statusText: 'Internal Server Error' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 3. updateConfiguration()
  // ─────────────────────────────────────────────────────────────────────────

  describe('updateConfiguration()', () => {
    it('should PUT /config/logs with the update request body', () => {
      const updateRequest: LogConfigUpdateRequest = {
        enabled: true,
        retentionDays: 14,
        maxEntriesPerJob: 20000
      };
      const response = makeConfigResponse({
        config: makeConfig({ retentionDays: 14, maxEntriesPerJob: 20000 })
      });

      service.updateConfiguration(updateRequest).subscribe(r => {
        expect(r.config.retentionDays).toBe(14);
        expect(r.config.maxEntriesPerJob).toBe(20000);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/config/logs'));
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(updateRequest);
      req.flush(response);
    });

    it('should send partial update with only changed fields', () => {
      const partialUpdate: LogConfigUpdateRequest = { enabled: false };

      service.updateConfiguration(partialUpdate).subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/config/logs'));
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ enabled: false });
      req.flush(makeConfigResponse({ config: makeConfig({ enabled: false }) }));
    });

    it('should send archive settings in update body', () => {
      const updateRequest: LogConfigUpdateRequest = {
        archiveEnabled: true,
        archivePath: '/new/archive/path',
        archiveOnCleanup: false
      };

      service.updateConfiguration(updateRequest).subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/config/logs'));
      expect(req.request.body.archiveEnabled).toBeTrue();
      expect(req.request.body.archivePath).toBe('/new/archive/path');
      expect(req.request.body.archiveOnCleanup).toBeFalse();
      req.flush(makeConfigResponse());
    });

    it('should propagate errors on update', () => {
      let errorCaught = false;
      service.updateConfiguration({ retentionDays: -1 }).subscribe({
        error: () => { errorCaught = true; }
      });
      httpMock.expectOne(r => r.url.endsWith('/config/logs')).flush(
        {}, { status: 400, statusText: 'Bad Request' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 4. getStatus()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getStatus()', () => {
    it('should GET /config/logs/status', () => {
      const status = makeStatus({ utilizationPercent: 45.5 });

      service.getStatus().subscribe(r => {
        expect(r.enabled).toBeTrue();
        expect(r.utilizationPercent).toBe(45.5);
        expect(r.activeSequenceTrackers).toBe(3);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/config/logs/status'));
      expect(req.request.method).toBe('GET');
      req.flush(status);
    });

    it('should return disabled status when logging is off', () => {
      const status = makeStatus({ enabled: false, totalLogEntries: 0, reason: 'Feature disabled' });

      service.getStatus().subscribe(r => {
        expect(r.enabled).toBeFalse();
        expect(r.reason).toBe('Feature disabled');
      });

      httpMock.expectOne(r => r.url.endsWith('/config/logs/status')).flush(status);
    });

    it('should propagate errors', () => {
      let errorCaught = false;
      service.getStatus().subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/config/logs/status')).flush(
        {}, { status: 503, statusText: 'Service Unavailable' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 5. triggerCleanup()
  // ─────────────────────────────────────────────────────────────────────────

  describe('triggerCleanup()', () => {
    it('should POST /config/logs/cleanup with default hoursToKeep=168', () => {
      const response = makeCleanupResponse();

      service.triggerCleanup().subscribe(r => {
        expect(r.success).toBeTrue();
        expect(r.deletedCount).toBe(500);
        expect(r.hoursRetained).toBe(168);
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/config/logs/cleanup') && r.params.get('hoursToKeep') === '168'
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      req.flush(response);
    });

    it('should pass custom hoursToKeep param', () => {
      service.triggerCleanup(48).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/config/logs/cleanup') && r.params.get('hoursToKeep') === '48'
      );
      expect(req.request.params.get('hoursToKeep')).toBe('48');
      req.flush(makeCleanupResponse({ hoursRetained: 48 }));
    });

    it('should propagate errors on cleanup', () => {
      let errorCaught = false;
      service.triggerCleanup().subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/config/logs/cleanup')).flush(
        {}, { status: 500, statusText: 'Server Error' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 6. enable()
  // ─────────────────────────────────────────────────────────────────────────

  describe('enable()', () => {
    it('should POST /config/logs/enable with null body', () => {
      const response = { success: true, enabled: true, message: 'Logging enabled successfully' };

      service.enable().subscribe(r => {
        expect(r.success).toBeTrue();
        expect(r.enabled).toBeTrue();
        expect(r.message).toBe('Logging enabled successfully');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/config/logs/enable'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      req.flush(response);
    });

    it('should propagate errors', () => {
      let errorCaught = false;
      service.enable().subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/config/logs/enable')).flush(
        {}, { status: 500, statusText: 'Server Error' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 7. disable()
  // ─────────────────────────────────────────────────────────────────────────

  describe('disable()', () => {
    it('should POST /config/logs/disable with null body', () => {
      const response = { success: true, enabled: false, message: 'Logging disabled successfully' };

      service.disable().subscribe(r => {
        expect(r.success).toBeTrue();
        expect(r.enabled).toBeFalse();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/config/logs/disable'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      req.flush(response);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 8. listArchives()
  // ─────────────────────────────────────────────────────────────────────────

  describe('listArchives()', () => {
    it('should GET /config/logs/archives', () => {
      const response = makeArchiveListResponse({
        archives: [
          makeArchiveInfo({ fileName: 'logs-2025-01-01.json.gz' }),
          makeArchiveInfo({ fileName: 'logs-2025-01-02.json.gz' })
        ]
      });

      service.listArchives().subscribe(r => {
        expect(r.available).toBeTrue();
        expect(r.archiveEnabled).toBeTrue();
        expect(r.archives.length).toBe(2);
        expect(r.archives[0].fileName).toBe('logs-2025-01-01.json.gz');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/config/logs/archives'));
      expect(req.request.method).toBe('GET');
      req.flush(response);
    });

    it('should return empty archives list when none exist', () => {
      const response = makeArchiveListResponse({ archives: [] });

      service.listArchives().subscribe(r => {
        expect(r.archives.length).toBe(0);
      });

      httpMock.expectOne(r => r.url.endsWith('/config/logs/archives')).flush(response);
    });

    it('should propagate errors', () => {
      let errorCaught = false;
      service.listArchives().subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/config/logs/archives')).flush(
        {}, { status: 404, statusText: 'Not Found' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 9. createArchive()
  // ─────────────────────────────────────────────────────────────────────────

  describe('createArchive()', () => {
    it('should POST /config/logs/archives/create with null body', () => {
      const response: ArchiveCreateResponse = {
        success: true,
        archivePath: '/var/log/kompile/archives',
        fileName: 'logs-full-2025-01-15.json.gz',
        message: 'Archive created successfully'
      };

      service.createArchive().subscribe(r => {
        expect(r.success).toBeTrue();
        expect(r.fileName).toBe('logs-full-2025-01-15.json.gz');
        expect(r.message).toBe('Archive created successfully');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/config/logs/archives/create'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      req.flush(response);
    });

    it('should propagate errors when archive creation fails', () => {
      let errorCaught = false;
      service.createArchive().subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.endsWith('/archives/create')).flush(
        {}, { status: 500, statusText: 'Server Error' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 10. archiveTaskLogs()
  // ─────────────────────────────────────────────────────────────────────────

  describe('archiveTaskLogs()', () => {
    it('should POST /config/logs/archives/task/:taskId with null body', () => {
      const response: ArchiveCreateResponse = {
        success: true,
        archivePath: '/var/log/kompile/archives',
        fileName: 'task-001-2025-01-15.json.gz',
        message: 'Task archive created'
      };

      service.archiveTaskLogs('task-001').subscribe(r => {
        expect(r.success).toBeTrue();
        expect(r.fileName).toBe('task-001-2025-01-15.json.gz');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/config/logs/archives/task/task-001'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      req.flush(response);
    });

    it('should return success=false when archiving fails gracefully', () => {
      const response: ArchiveCreateResponse = {
        success: false,
        message: 'No logs found for task'
      };

      service.archiveTaskLogs('task-empty').subscribe(r => {
        expect(r.success).toBeFalse();
        expect(r.message).toBe('No logs found for task');
      });

      httpMock.expectOne(r => r.url.endsWith('/archives/task/task-empty')).flush(response);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 11. getArchiveDownloadUrl()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getArchiveDownloadUrl()', () => {
    it('should return the correct download URL for a given filename', () => {
      const url = service.getArchiveDownloadUrl('logs-2025-01-01.json.gz');
      expect(url).toContain('/config/logs/archives/download/');
      expect(url).toContain('logs-2025-01-01.json.gz');
    });

    it('should URL-encode the filename', () => {
      const url = service.getArchiveDownloadUrl('logs 2025-01-01.json.gz');
      expect(url).toContain(encodeURIComponent('logs 2025-01-01.json.gz'));
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 12. deleteArchive()
  // ─────────────────────────────────────────────────────────────────────────

  describe('deleteArchive()', () => {
    it('should DELETE /config/logs/archives/:fileName', () => {
      const deleteResponse = {
        success: true,
        fileName: 'logs-2025-01-01.json.gz',
        message: 'Archive deleted successfully'
      };

      service.deleteArchive('logs-2025-01-01.json.gz').subscribe(r => {
        expect(r.success).toBeTrue();
        expect(r.fileName).toBe('logs-2025-01-01.json.gz');
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/config/logs/archives/logs-2025-01-01.json.gz')
      );
      expect(req.request.method).toBe('DELETE');
      req.flush(deleteResponse);
    });

    it('should URL-encode the filename in the DELETE request', () => {
      service.deleteArchive('logs 2025-01-01.json.gz').subscribe();

      const req = httpMock.expectOne(r =>
        r.url.includes(encodeURIComponent('logs 2025-01-01.json.gz'))
      );
      expect(req.request.method).toBe('DELETE');
      req.flush({ success: true, fileName: 'logs 2025-01-01.json.gz', message: 'Deleted' });
    });

    it('should propagate 404 errors when archive not found', () => {
      let errorCaught = false;
      service.deleteArchive('nonexistent.gz').subscribe({ error: () => { errorCaught = true; } });
      httpMock.expectOne(r => r.url.includes('nonexistent.gz')).flush(
        {}, { status: 404, statusText: 'Not Found' }
      );
      expect(errorCaught).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 13. formatFileSize() utility
  // ─────────────────────────────────────────────────────────────────────────

  describe('formatFileSize()', () => {
    it('should format bytes under 1 KB', () => {
      expect(service.formatFileSize(512)).toBe('512 B');
    });

    it('should format kilobytes', () => {
      expect(service.formatFileSize(2048)).toBe('2.0 KB');
    });

    it('should format megabytes', () => {
      expect(service.formatFileSize(5 * 1024 * 1024)).toBe('5.0 MB');
    });

    it('should format gigabytes', () => {
      expect(service.formatFileSize(2 * 1024 * 1024 * 1024)).toBe('2.0 GB');
    });

    it('should format exact boundary of 1024 as 1.0 KB', () => {
      expect(service.formatFileSize(1024)).toBe('1.0 KB');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 14. formatCount() utility
  // ─────────────────────────────────────────────────────────────────────────

  describe('formatCount()', () => {
    it('should return count as string for values under 1000', () => {
      expect(service.formatCount(999)).toBe('999');
      expect(service.formatCount(0)).toBe('0');
    });

    it('should format thousands as K', () => {
      expect(service.formatCount(1500)).toBe('1.5K');
      expect(service.formatCount(10000)).toBe('10.0K');
    });

    it('should format millions as M', () => {
      expect(service.formatCount(1500000)).toBe('1.5M');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 15. getUtilizationClass() utility
  // ─────────────────────────────────────────────────────────────────────────

  describe('getUtilizationClass()', () => {
    it('should return utilization-low for percent < 50', () => {
      expect(service.getUtilizationClass(0)).toBe('utilization-low');
      expect(service.getUtilizationClass(49)).toBe('utilization-low');
    });

    it('should return utilization-medium for percent between 50 and 79', () => {
      expect(service.getUtilizationClass(50)).toBe('utilization-medium');
      expect(service.getUtilizationClass(79)).toBe('utilization-medium');
    });

    it('should return utilization-high for percent >= 80', () => {
      expect(service.getUtilizationClass(80)).toBe('utilization-high');
      expect(service.getUtilizationClass(100)).toBe('utilization-high');
    });
  });
});
