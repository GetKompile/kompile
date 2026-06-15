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
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

import { ConfigArchiveManagerComponent } from './config-archive-manager.component';

describe('ConfigArchiveManagerComponent', () => {
  let component: ConfigArchiveManagerComponent;
  let fixture: ComponentFixture<ConfigArchiveManagerComponent>;
  let httpMock: HttpTestingController;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  const mockManifest = {
    version: '1.0',
    createdAt: '2025-01-01T00:00:00Z',
    hostname: 'localhost',
    description: null,
    kompileConfigs: ['app.properties'],
    chatProviderConfigs: { claude: ['claude.json'], gemini: ['gemini.json'] },
    systemPrompts: ['system.txt']
  };

  const mockArchive = {
    fileName: 'kompile-config-2025-01-01.zip',
    filePath: '/tmp/kompile-config-2025-01-01.zip',
    sizeBytes: 2048,
    lastModified: '2025-01-01T00:00:00Z',
    manifest: mockManifest
  };

  beforeEach(async () => {
    const snackSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    await TestBed.configureTestingModule({
      imports: [
        ConfigArchiveManagerComponent,
        HttpClientTestingModule,
        CommonModule,
        FormsModule,
        NoopAnimationsModule
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
      .overrideProvider(MatSnackBar, { useValue: snackSpy })
      .overrideComponent(ConfigArchiveManagerComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .compileComponents();

    snackBarSpy = TestBed.inject(MatSnackBar) as jasmine.SpyObj<MatSnackBar>;
    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ConfigArchiveManagerComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should create', () => {
    fixture.detectChanges();
    const req = httpMock.expectOne('/api/config-archives');
    req.flush({ archives: [], total: 0 });
    expect(component).toBeTruthy();
  });

  describe('ngOnInit / loadArchives', () => {
    it('should load archives on init', fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [mockArchive], total: 1 });
      tick();

      expect(component.archives.length).toBe(1);
      expect(component.isLoading).toBeFalse();
    }));

    it('should show snackbar on load failure', fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.error(new ProgressEvent('error'));
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to load archives', 'Dismiss', { duration: 3000 });
      expect(component.isLoading).toBeFalse();
    }));
  });

  describe('exportAndSave', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });
      tick();
    }));

    it('should POST to export/save with description when provided', fakeAsync(() => {
      component.exportDescription = 'My snapshot';
      component.exportAndSave();
      expect(component.isExporting).toBeTrue();

      const req = httpMock.expectOne(r =>
        r.url === '/api/config-archives/export/save' && r.params.get('description') === 'My snapshot'
      );
      req.flush({ message: 'Archive saved: test.zip', fileName: 'test.zip' });
      // flush the reload triggered by loadArchives()
      const reload = httpMock.expectOne('/api/config-archives');
      reload.flush({ archives: [], total: 0 });
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Archive saved: test.zip', 'Dismiss', { duration: 3000 });
      expect(component.exportDescription).toBe('');
      expect(component.isExporting).toBeFalse();
    }));

    it('should POST to export/save without description param when description is blank', fakeAsync(() => {
      component.exportDescription = '';
      component.exportAndSave();

      const req = httpMock.expectOne(r =>
        r.url === '/api/config-archives/export/save' && !r.params.has('description')
      );
      req.flush({ message: 'Archive saved: test.zip', fileName: 'test.zip' });
      // flush the reload
      const reload = httpMock.expectOne('/api/config-archives');
      reload.flush({ archives: [], total: 0 });
      tick();

      expect(component.isExporting).toBeFalse();
    }));

    it('should show snackbar on export failure', fakeAsync(() => {
      component.exportAndSave();
      const req = httpMock.expectOne(r => r.url === '/api/config-archives/export/save');
      req.error(new ProgressEvent('error'));
      tick();

      expect(snackBarSpy.open).toHaveBeenCalled();
      expect(component.isExporting).toBeFalse();
    }));
  });

  describe('exportAndDownload', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });
      tick();
    }));

    it('should POST to export and trigger download', fakeAsync(() => {
      spyOn(window.URL, 'createObjectURL').and.returnValue('blob:mock');
      spyOn(window.URL, 'revokeObjectURL');
      const anchor = jasmine.createSpyObj('a', ['click']);
      spyOn(document, 'createElement').and.returnValue(anchor);

      component.exportAndDownload();
      const req = httpMock.expectOne(r => r.url === '/api/config-archives/export');
      expect(req.request.method).toBe('POST');
      req.flush(new Blob(['zip content']), { headers: { 'Content-Type': 'application/zip' } });
      tick();

      expect(anchor.click).toHaveBeenCalled();
      expect(component.isExporting).toBeFalse();
    }));

    it('should show snackbar on download failure', fakeAsync(() => {
      component.exportAndDownload();
      const req = httpMock.expectOne(r => r.url === '/api/config-archives/export');
      req.error(new ProgressEvent('error'));
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Export failed', 'Dismiss', { duration: 3000 });
    }));
  });

  describe('onFileSelected', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });
      tick();
    }));

    it('should set selectedFile and call previewFile', fakeAsync(() => {
      const file = new File(['zip content'], 'test.zip', { type: 'application/zip' });
      const input = { files: [file] } as any;
      const event = { target: input } as any;

      component.onFileSelected(event);
      expect(component.selectedFile).toBe(file);

      const req = httpMock.expectOne('/api/config-archives/preview');
      req.flush({ manifest: mockManifest });
      tick();

      expect(component.previewManifest).toEqual(mockManifest);
    }));

    it('should show snackbar on preview failure', fakeAsync(() => {
      const file = new File(['bad'], 'bad.zip');
      const event = { target: { files: [file] } } as any;
      component.onFileSelected(event);

      const req = httpMock.expectOne('/api/config-archives/preview');
      req.error(new ProgressEvent('error'));
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Invalid archive file', 'Dismiss', { duration: 3000 });
    }));

    it('should do nothing when no files selected', () => {
      const event = { target: { files: [] } } as any;
      component.onFileSelected(event);
      expect(component.selectedFile).toBeNull();
    });
  });

  describe('importFile', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });
      tick();
    }));

    it('should do nothing if no file selected', fakeAsync(() => {
      component.selectedFile = null;
      component.importFile();
      tick();
      httpMock.expectNone('/api/config-archives/import');
      expect(component.isImporting).toBeFalse();
    }));

    it('should POST to import and show snackbar on success', fakeAsync(() => {
      component.selectedFile = new File(['zip'], 'test.zip');
      component.importMode = 'append';

      component.importFile();
      const req = httpMock.expectOne(r =>
        r.url === '/api/config-archives/import' && r.params.get('mode') === 'append'
      );
      req.flush({
        message: 'Import complete',
        mode: 'append',
        created: [],
        overwritten: [],
        merged: [],
        skipped: [],
        totalProcessed: 5
      });
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Import complete: 5 files processed', 'Dismiss', { duration: 5000 });
      expect(component.isImporting).toBeFalse();
    }));

    it('should show error snackbar on import failure', fakeAsync(() => {
      component.selectedFile = new File(['zip'], 'test.zip');
      component.importFile();
      const req = httpMock.expectOne(r => r.url === '/api/config-archives/import');
      req.error(new ProgressEvent('error'));
      tick();

      expect(snackBarSpy.open).toHaveBeenCalled();
      expect(component.isImporting).toBeFalse();
    }));
  });

  describe('importSaved', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });
      tick();
    }));

    it('should POST to import/{fileName} and show snackbar on success', fakeAsync(() => {
      component.importMode = 'override';
      component.importSaved(mockArchive as any);

      const req = httpMock.expectOne(r =>
        r.url === `/api/config-archives/import/${mockArchive.fileName}` && r.params.get('mode') === 'override'
      );
      req.flush({
        message: 'Import complete',
        mode: 'override',
        created: ['app.properties'],
        overwritten: [],
        merged: [],
        skipped: [],
        totalProcessed: 1
      });
      tick();

      expect(snackBarSpy.open).toHaveBeenCalled();
      expect(component.isImporting).toBeFalse();
    }));

    it('should show error snackbar on importSaved failure', fakeAsync(() => {
      component.importSaved(mockArchive as any);
      const req = httpMock.expectOne(r =>
        r.url === `/api/config-archives/import/${mockArchive.fileName}`
      );
      req.error(new ProgressEvent('error'));
      tick();
      expect(snackBarSpy.open).toHaveBeenCalled();
    }));
  });

  describe('downloadArchive', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });
      tick();
    }));

    it('should GET download endpoint and trigger anchor click', fakeAsync(() => {
      spyOn(window.URL, 'createObjectURL').and.returnValue('blob:mock');
      spyOn(window.URL, 'revokeObjectURL');
      const anchor = jasmine.createSpyObj('a', ['click']);
      spyOn(document, 'createElement').and.returnValue(anchor);

      component.downloadArchive(mockArchive as any);
      const req = httpMock.expectOne(`/api/config-archives/${mockArchive.fileName}/download`);
      req.flush(new Blob(['zip']));
      tick();

      expect(anchor.click).toHaveBeenCalled();
    }));

    it('should show snackbar on download failure', fakeAsync(() => {
      component.downloadArchive(mockArchive as any);
      const req = httpMock.expectOne(r => r.url.includes('/download'));
      req.error(new ProgressEvent('error'));
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Download failed', 'Dismiss', { duration: 3000 });
    }));
  });

  describe('deleteArchive', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });
      tick();
    }));

    it('should prompt and DELETE the archive on confirm', fakeAsync(() => {
      spyOn(window, 'confirm').and.returnValue(true);
      component.deleteArchive(mockArchive as any);

      const req = httpMock.expectOne(`/api/config-archives/${mockArchive.fileName}`);
      expect(req.request.method).toBe('DELETE');
      req.flush({ message: 'Archive deleted' });
      const reload = httpMock.expectOne('/api/config-archives');
      reload.flush({ archives: [], total: 0 });
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Archive deleted', 'Dismiss', { duration: 3000 });
    }));

    it('should not delete if user cancels confirm', fakeAsync(() => {
      spyOn(window, 'confirm').and.returnValue(false);
      component.deleteArchive(mockArchive as any);
      tick();
      httpMock.expectNone(`/api/config-archives/${mockArchive.fileName}`);
      expect(component.archives.length).toBe(0); // no change
    }));

    it('should show snackbar on delete failure', fakeAsync(() => {
      spyOn(window, 'confirm').and.returnValue(true);
      component.deleteArchive(mockArchive as any);
      const req = httpMock.expectOne(r =>
        r.url === `/api/config-archives/${mockArchive.fileName}` && r.method === 'DELETE'
      );
      req.error(new ProgressEvent('error'));
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Delete failed', 'Dismiss', { duration: 3000 });
    }));
  });

  describe('clearImportSelection', () => {
    it('should clear selectedFile, previewManifest, lastImportResult', () => {
      // flush init request
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });

      component.selectedFile = new File(['zip'], 'test.zip');
      component.previewManifest = mockManifest;
      component.lastImportResult = {
        message: 'done', mode: 'append',
        created: [], overwritten: [], merged: [], skipped: [], totalProcessed: 0
      };
      component.clearImportSelection();
      expect(component.selectedFile).toBeNull();
      expect(component.previewManifest).toBeNull();
      expect(component.lastImportResult).toBeNull();
    });
  });

  describe('formatSize', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });
      tick();
    }));

    it('should format bytes < 1024', () => {
      expect(component.formatSize(512)).toBe('512 B');
    });
    it('should format KB', () => {
      expect(component.formatSize(2048)).toBe('2.0 KB');
    });
    it('should format MB', () => {
      expect(component.formatSize(2 * 1024 * 1024)).toBe('2.0 MB');
    });
  });

  describe('formatDate', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });
      tick();
    }));

    it('should format valid ISO date', () => {
      const result = component.formatDate('2025-01-01T00:00:00Z');
      expect(typeof result).toBe('string');
      expect(result.length).toBeGreaterThan(0);
    });
  });

  describe('getProviderNames', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });
      tick();
    }));

    it('should return provider keys', () => {
      const names = component.getProviderNames(mockManifest);
      expect(names).toContain('claude');
      expect(names).toContain('gemini');
    });
  });

  describe('getProviderIcon', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });
      tick();
    }));

    it('should return specific icon for known providers', () => {
      expect(component.getProviderIcon('claude')).toBe('smart_toy');
      expect(component.getProviderIcon('gemini')).toBe('auto_awesome');
      expect(component.getProviderIcon('codex')).toBe('code');
    });

    it('should return default icon for unknown provider', () => {
      expect(component.getProviderIcon('unknown')).toBe('settings');
    });
  });

  describe('getTotalFileCount', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config-archives');
      req.flush({ archives: [], total: 0 });
      tick();
    }));

    it('should count all files across sections', () => {
      // mockManifest: 1 kompileConfig + 1 claude + 1 gemini + 1 systemPrompt = 4
      expect(component.getTotalFileCount(mockManifest)).toBe(4);
    });

    it('should return 0 for empty manifest sections', () => {
      const emptyManifest = {
        ...mockManifest,
        kompileConfigs: [],
        chatProviderConfigs: {},
        systemPrompts: []
      };
      expect(component.getTotalFileCount(emptyManifest)).toBe(0);
    });
  });
});
