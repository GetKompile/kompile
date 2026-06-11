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

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { of, throwError } from 'rxjs';

import { LogSettingsComponent } from './log-settings.component';
import {
  LogConfigService,
  LogConfigResponse,
  LogStatus,
  ArchiveListResponse,
  ArchiveInfo
} from '../../../services/log-config.service';

function makeStatus(overrides: Partial<LogStatus> = {}): LogStatus {
  return {
    enabled: true,
    totalLogEntries: 100,
    maxTotalEntries: 500000,
    utilizationPercent: 0.02,
    retentionDays: 7,
    activeSequenceTrackers: 0,
    ...overrides
  };
}

function makeConfigResponse(overrides: any = {}): LogConfigResponse {
  return {
    available: true,
    config: {
      enabled: true,
      retentionDays: 7,
      maxEntriesPerJob: 10000,
      maxTotalEntries: 500000,
      archiveEnabled: false,
      archivePath: '',
      archiveOnCleanup: false
    },
    status: makeStatus(),
    ...overrides
  };
}

function makeArchiveListResponse(overrides: Partial<ArchiveListResponse> = {}): ArchiveListResponse {
  return {
    available: true,
    archiveEnabled: false,
    archivePath: '/tmp/archives',
    archiveOnCleanup: false,
    archives: [],
    ...overrides
  };
}

describe('LogSettingsComponent', () => {
  let component: LogSettingsComponent;
  let fixture: ComponentFixture<LogSettingsComponent>;
  let logConfigServiceSpy: jasmine.SpyObj<LogConfigService>;

  beforeEach(async () => {
    const spy = jasmine.createSpyObj('LogConfigService', [
      'getConfiguration',
      'updateConfiguration',
      'getStatus',
      'triggerCleanup',
      'enable',
      'disable',
      'listArchives',
      'createArchive',
      'getArchiveDownloadUrl',
      'deleteArchive',
      'formatFileSize',
      'formatCount',
      'getUtilizationClass'
    ]);

    spy.getConfiguration.and.returnValue(of(makeConfigResponse()));
    spy.listArchives.and.returnValue(of(makeArchiveListResponse()));
    spy.formatFileSize.and.callFake((bytes: number) => bytes + ' B');
    spy.formatCount.and.callFake((n: number) => n.toString());
    spy.getUtilizationClass.and.callFake((p: number) => 'utilization-low');
    spy.getArchiveDownloadUrl.and.returnValue('/api/config/logs/archives/download/test.zip');

    await TestBed.configureTestingModule({
      imports: [
        LogSettingsComponent,
        CommonModule,
        FormsModule,
        NoopAnimationsModule
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
      .overrideProvider(LogConfigService, { useValue: spy })
      .overrideComponent(LogSettingsComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .compileComponents();

    logConfigServiceSpy = TestBed.inject(LogConfigService) as jasmine.SpyObj<LogConfigService>;
    fixture = TestBed.createComponent(LogSettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('ngOnInit', () => {
    it('should call loadConfiguration and loadArchives on init', () => {
      expect(logConfigServiceSpy.getConfiguration).toHaveBeenCalled();
      expect(logConfigServiceSpy.listArchives).toHaveBeenCalled();
    });
  });

  describe('loadConfiguration', () => {
    it('should set available and config from response', fakeAsync(() => {
      const resp = makeConfigResponse({ available: true });
      logConfigServiceSpy.getConfiguration.and.returnValue(of(resp));
      component.loadConfiguration();
      tick();

      expect(component.available).toBeTrue();
      expect(component.config.retentionDays).toBe(7);
      expect(component.loading).toBeFalse();
    }));

    it('should set error message on failure', fakeAsync(() => {
      logConfigServiceSpy.getConfiguration.and.returnValue(
        throwError(() => ({ message: 'network error' }))
      );
      component.loadConfiguration();
      tick();

      expect(component.error).toContain('Failed to load log configuration');
      expect(component.loading).toBeFalse();
    }));
  });

  describe('saveConfiguration', () => {
    it('should call updateConfiguration and set successMessage', fakeAsync(() => {
      logConfigServiceSpy.updateConfiguration.and.returnValue(of(makeConfigResponse()));
      component.saveConfiguration();
      tick();

      expect(logConfigServiceSpy.updateConfiguration).toHaveBeenCalled();
      expect(component.successMessage).toBe('Configuration saved successfully');
      expect(component.saving).toBeFalse();
    }));

    it('should clear successMessage after 3 seconds', fakeAsync(() => {
      logConfigServiceSpy.updateConfiguration.and.returnValue(of(makeConfigResponse()));
      component.saveConfiguration();
      tick();
      expect(component.successMessage).toBe('Configuration saved successfully');
      tick(3000);
      expect(component.successMessage).toBeNull();
    }));

    it('should set error on failure', fakeAsync(() => {
      logConfigServiceSpy.updateConfiguration.and.returnValue(
        throwError(() => ({ message: 'save failed' }))
      );
      component.saveConfiguration();
      tick();

      expect(component.error).toContain('Failed to save configuration');
      expect(component.saving).toBeFalse();
    }));
  });

  describe('toggleEnabled', () => {
    it('should call enable when config.enabled is true', fakeAsync(() => {
      component.config.enabled = true;
      logConfigServiceSpy.enable.and.returnValue(
        of({ success: true, enabled: true, message: 'Enabled' })
      );
      component.toggleEnabled();
      tick();
      expect(logConfigServiceSpy.enable).toHaveBeenCalled();
      expect(component.config.enabled).toBeTrue();
      expect(component.successMessage).toBe('Enabled');
    }));

    it('should call disable when config.enabled is false', fakeAsync(() => {
      component.config.enabled = false;
      logConfigServiceSpy.disable.and.returnValue(
        of({ success: true, enabled: false, message: 'Disabled' })
      );
      component.toggleEnabled();
      tick();
      expect(logConfigServiceSpy.disable).toHaveBeenCalled();
    }));

    it('should revert enabled on error', fakeAsync(() => {
      component.config.enabled = true;
      logConfigServiceSpy.enable.and.returnValue(
        throwError(() => ({ message: 'toggle failed' }))
      );
      component.toggleEnabled();
      tick();
      expect(component.config.enabled).toBeFalse(); // reverted
      expect(component.error).toContain('Failed to toggle logging');
    }));
  });

  describe('openCleanupDialog / closeCleanupDialog', () => {
    it('should open and close dialog', () => {
      component.openCleanupDialog();
      expect(component.showCleanupDialog).toBeTrue();
      component.closeCleanupDialog();
      expect(component.showCleanupDialog).toBeFalse();
    });
  });

  describe('triggerCleanup', () => {
    it('should call triggerCleanup service and update status', fakeAsync(() => {
      const statusAfter = makeStatus({ totalLogEntries: 50 });
      logConfigServiceSpy.triggerCleanup.and.returnValue(
        of({ success: true, deletedCount: 50, hoursRetained: 168, statusBefore: makeStatus(), statusAfter })
      );
      component.showCleanupDialog = true;
      component.triggerCleanup();
      tick();

      expect(component.status).toEqual(statusAfter);
      expect(component.successMessage).toContain('50');
      expect(component.showCleanupDialog).toBeFalse();
    }));

    it('should set error on cleanup failure', fakeAsync(() => {
      logConfigServiceSpy.triggerCleanup.and.returnValue(
        throwError(() => ({ message: 'cleanup failed' }))
      );
      component.triggerCleanup();
      tick();
      expect(component.error).toContain('Failed to cleanup logs');
      expect(component.cleanupLoading).toBeFalse();
    }));
  });

  describe('refreshStatus', () => {
    it('should update status from service', fakeAsync(() => {
      const newStatus = makeStatus({ totalLogEntries: 200 });
      logConfigServiceSpy.getStatus.and.returnValue(of(newStatus));
      component.refreshStatus();
      tick();
      expect(component.status).toEqual(newStatus);
    }));

    it('should log error on failure', fakeAsync(() => {
      spyOn(console, 'error');
      logConfigServiceSpy.getStatus.and.returnValue(
        throwError(() => ({ message: 'status error' }))
      );
      component.refreshStatus();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  describe('getUtilizationColor', () => {
    it('should return primary for < 50%', () => {
      expect(component.getUtilizationColor(30)).toBe('primary');
    });
    it('should return accent for 50-79%', () => {
      expect(component.getUtilizationColor(60)).toBe('accent');
    });
    it('should return warn for >= 80%', () => {
      expect(component.getUtilizationColor(80)).toBe('warn');
    });
  });

  describe('loadArchives', () => {
    it('should populate archives and update config from response', fakeAsync(() => {
      const archive: ArchiveInfo = {
        fileName: 'test.zip',
        filePath: '/tmp/test.zip',
        sizeBytes: 1024,
        sizeFormatted: '1 KB',
        createdAt: '2025-01-01T00:00:00Z'
      };
      logConfigServiceSpy.listArchives.and.returnValue(of({
        ...makeArchiveListResponse(),
        archives: [archive],
        archiveEnabled: true,
        archivePath: '/custom/path'
      }));
      component.loadArchives();
      tick();

      expect(component.archives.length).toBe(1);
      expect(component.config.archiveEnabled).toBeTrue();
      expect(component.config.archivePath).toBe('/custom/path');
    }));

    it('should log error on failure', fakeAsync(() => {
      spyOn(console, 'error');
      logConfigServiceSpy.listArchives.and.returnValue(
        throwError(() => ({ message: 'list failed' }))
      );
      component.loadArchives();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  describe('createArchive', () => {
    it('should set successMessage on successful archive creation', fakeAsync(() => {
      logConfigServiceSpy.createArchive.and.returnValue(
        of({ success: true, message: 'Archive created successfully' })
      );
      component.createArchive();
      tick();
      expect(component.successMessage).toBe('Archive created successfully');
      expect(component.archiveCreating).toBeFalse();
    }));

    it('should handle success=false with fallback message', fakeAsync(() => {
      logConfigServiceSpy.createArchive.and.returnValue(
        of({ success: false, message: 'No logs to archive' })
      );
      component.createArchive();
      tick();
      expect(component.successMessage).toBe('No logs to archive');
    }));

    it('should set error on create archive failure', fakeAsync(() => {
      logConfigServiceSpy.createArchive.and.returnValue(
        throwError(() => ({ message: 'create failed' }))
      );
      component.createArchive();
      tick();
      expect(component.error).toContain('Failed to create archive');
      expect(component.archiveCreating).toBeFalse();
    }));
  });

  describe('downloadArchive', () => {
    it('should open archive download URL in new window', () => {
      spyOn(window, 'open');
      const archive: ArchiveInfo = {
        fileName: 'test.zip',
        filePath: '/tmp/test.zip',
        sizeBytes: 1024,
        sizeFormatted: '1 KB',
        createdAt: '2025-01-01T00:00:00Z'
      };
      logConfigServiceSpy.getArchiveDownloadUrl.and.returnValue('/api/config/logs/archives/download/test.zip');
      component.downloadArchive(archive);
      expect(window.open).toHaveBeenCalledWith('/api/config/logs/archives/download/test.zip', '_blank');
    });
  });

  describe('openArchiveDeleteDialog / closeArchiveDeleteDialog', () => {
    it('should set archiveToDelete and show dialog', () => {
      const archive: ArchiveInfo = {
        fileName: 'test.zip',
        filePath: '/tmp',
        sizeBytes: 0,
        sizeFormatted: '0 B',
        createdAt: ''
      };
      component.openArchiveDeleteDialog(archive);
      expect(component.archiveToDelete).toEqual(archive);
      expect(component.showArchiveDeleteDialog).toBeTrue();

      component.closeArchiveDeleteDialog();
      expect(component.archiveToDelete).toBeNull();
      expect(component.showArchiveDeleteDialog).toBeFalse();
    });
  });

  describe('confirmDeleteArchive', () => {
    const archive: ArchiveInfo = {
      fileName: 'del.zip',
      filePath: '/tmp/del.zip',
      sizeBytes: 512,
      sizeFormatted: '512 B',
      createdAt: '2025-01-01T00:00:00Z'
    };

    it('should do nothing if archiveToDelete is null', () => {
      component.archiveToDelete = null;
      component.confirmDeleteArchive();
      expect(logConfigServiceSpy.deleteArchive).not.toHaveBeenCalled();
    });

    it('should call deleteArchive and set successMessage on success', fakeAsync(() => {
      component.archiveToDelete = archive;
      logConfigServiceSpy.deleteArchive.and.returnValue(
        of({ success: true, fileName: 'del.zip', message: 'Archive deleted successfully' })
      );
      component.confirmDeleteArchive();
      tick();
      expect(component.successMessage).toBe('Archive deleted successfully');
      expect(component.archiveDeleting).toBeFalse();
    }));

    it('should set error on delete failure', fakeAsync(() => {
      component.archiveToDelete = archive;
      logConfigServiceSpy.deleteArchive.and.returnValue(
        throwError(() => ({ message: 'delete failed' }))
      );
      component.confirmDeleteArchive();
      tick();
      expect(component.error).toContain('Failed to delete archive');
    }));
  });

  describe('formatArchiveDate', () => {
    it('should return Unknown for empty string', () => {
      expect(component.formatArchiveDate('')).toBe('Unknown');
    });

    it('should format a valid ISO date string', () => {
      const result = component.formatArchiveDate('2025-01-01T00:00:00Z');
      expect(typeof result).toBe('string');
      expect(result.length).toBeGreaterThan(0);
    });
  });

  describe('ngOnDestroy', () => {
    it('should complete the destroy subject', () => {
      spyOn((component as any).destroy$, 'next').and.callThrough();
      spyOn((component as any).destroy$, 'complete').and.callThrough();
      component.ngOnDestroy();
      expect((component as any).destroy$.next).toHaveBeenCalled();
      expect((component as any).destroy$.complete).toHaveBeenCalled();
    });
  });
});
