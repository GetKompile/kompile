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
import { NO_ERRORS_SCHEMA, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { HttpClient } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { ChunkingLoaderTestComponent } from './chunking-loader-test.component';
import { DocumentService } from '../../services/document.service';
import { OcrService } from '../../services/ocr.service';
import { OcrStatus, OcrConfig, OcrModelInfo } from '../../models/ocr-models';
import { LoaderInfo, ChunkerInfo } from '../../models/api-models';
import { PageEvent } from '@angular/material/paginator';

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

function makeOcrStatus(overrides: Partial<OcrStatus> = {}): OcrStatus {
  return {
    ocrEnabled: true,
    pipelineReady: true,
    postProcessorAvailable: false,
    ...overrides
  };
}

function makeFile(name: string, type = 'application/pdf', size = 1024): File {
  // Build a File with a specified approximate size
  const content = 'x'.repeat(size);
  return new File([content], name, { type });
}

function makeAnalysisResult(overrides: Partial<any> = {}): any {
  return {
    fileName: 'test.pdf',
    filePath: '/tmp/test.pdf',
    fileSize: 2048,
    availableLoaders: [],
    selectedLoader: null,
    loadedDocuments: [
      { id: 'doc-1', text: 'Hello world', contentLength: 11, hasContent: true, metadata: {}, contentStats: {} }
    ],
    availableChunkers: [],
    selectedChunker: null,
    chunks: [
      { id: 'c1', text: 'Hello', contentLength: 5, chunkIndex: 0, metadata: {} },
      { id: 'c2', text: 'world', contentLength: 5, chunkIndex: 1, metadata: {} }
    ],
    processingStats: {},
    errorMessage: null,
    ...overrides
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Spy factories
// ─────────────────────────────────────────────────────────────────────────────

function createDocumentServiceSpy(): jasmine.SpyObj<DocumentService> {
  const spy = jasmine.createSpyObj<DocumentService>('DocumentService', [
    'getAvailableLoaders',
    'getAvailableChunkers'
  ]);
  spy.getAvailableLoaders.and.returnValue(of([]));
  spy.getAvailableChunkers.and.returnValue(of([]));
  return spy;
}

function createOcrServiceSpy(): jasmine.SpyObj<OcrService> {
  const spy = jasmine.createSpyObj<OcrService>('OcrService', [
    'getStatus',
    'getConfig',
    'getModels'
  ]);
  spy.getStatus.and.returnValue(of(makeOcrStatus()));
  spy.getConfig.and.returnValue(of({} as OcrConfig));
  spy.getModels.and.returnValue(of([] as OcrModelInfo[]));
  return spy;
}

function createHttpClientSpy(): jasmine.SpyObj<HttpClient> {
  return jasmine.createSpyObj<HttpClient>('HttpClient', ['post', 'get']);
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

describe('ChunkingLoaderTestComponent', () => {
  let component: ChunkingLoaderTestComponent;
  let fixture: ComponentFixture<ChunkingLoaderTestComponent>;
  let documentServiceSpy: jasmine.SpyObj<DocumentService>;
  let ocrServiceSpy: jasmine.SpyObj<OcrService>;
  let httpClientSpy: jasmine.SpyObj<HttpClient>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  beforeEach(async () => {
    documentServiceSpy = createDocumentServiceSpy();
    ocrServiceSpy = createOcrServiceSpy();
    httpClientSpy = createHttpClientSpy();
    snackBarSpy = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
    .overrideComponent(ChunkingLoaderTestComponent, {
      set: {
        imports: [CommonModule, ReactiveFormsModule, FormsModule],
        template: '<div></div>',
        schemas: [NO_ERRORS_SCHEMA]
      }
    })
    .overrideProvider(DocumentService, { useValue: documentServiceSpy })
    .overrideProvider(OcrService, { useValue: ocrServiceSpy })
    .overrideProvider(HttpClient, { useValue: httpClientSpy })
    .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
    .compileComponents();

    fixture = TestBed.createComponent(ChunkingLoaderTestComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    component.ngOnDestroy();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 1. Component creation and init
  // ─────────────────────────────────────────────────────────────────────────

  describe('Component creation', () => {
    it('should create the component', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should call loadAvailableLoadersAndChunkers and loadOcrStatus on init', () => {
      fixture.detectChanges();
      expect(documentServiceSpy.getAvailableLoaders).toHaveBeenCalled();
      expect(documentServiceSpy.getAvailableChunkers).toHaveBeenCalled();
      expect(ocrServiceSpy.getStatus).toHaveBeenCalled();
    });

    it('should initialize with default state', () => {
      expect(component.sourceType).toBe('file');
      expect(component.selectedFile).toBeNull();
      expect(component.selectedFiles).toEqual([]);
      expect(component.isUploading).toBeFalse();
      expect(component.isAnalyzing).toBeFalse();
      expect(component.analysisResult).toBeNull();
      expect(component.isDragOver).toBeFalse();
      expect(component.chunkViewMode).toBe('cards');
      expect(component.selectedChunkIndex).toBeNull();
    });

    it('should populate availableLoaders from DocumentService on init', fakeAsync(() => {
      const loaders: LoaderInfo[] = [
        { name: 'pdf', className: 'PdfLoader' },
        { name: 'tika', className: 'TikaLoader' }
      ];
      documentServiceSpy.getAvailableLoaders.and.returnValue(of(loaders));

      fixture.detectChanges();
      tick();

      expect(component.availableLoaders.length).toBe(2);
      expect(component.availableLoaders[0].name).toBe('pdf');
    }));

    it('should populate availableChunkers from DocumentService on init', fakeAsync(() => {
      const chunkers: ChunkerInfo[] = [
        { name: 'sentence', className: 'SentenceChunker' }
      ];
      documentServiceSpy.getAvailableChunkers.and.returnValue(of(chunkers));

      fixture.detectChanges();
      tick();

      expect(component.availableChunkers.length).toBe(1);
    }));

    it('should set ocrStatus from OcrService on init', fakeAsync(() => {
      ocrServiceSpy.getStatus.and.returnValue(of(makeOcrStatus({ ocrEnabled: true, pipelineReady: true })));

      fixture.detectChanges();
      tick();

      expect(component.ocrStatus).toEqual(jasmine.objectContaining({ ocrEnabled: true, pipelineReady: true }));
      expect(component.isLoadingOcrStatus).toBeFalse();
    }));

    it('should handle getAvailableLoaders error gracefully', fakeAsync(() => {
      documentServiceSpy.getAvailableLoaders.and.returnValue(throwError(() => new Error('network error')));

      fixture.detectChanges();
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to load available loaders', 'Dismiss', jasmine.any(Object));
    }));

    it('should handle getAvailableChunkers error gracefully', fakeAsync(() => {
      documentServiceSpy.getAvailableChunkers.and.returnValue(throwError(() => new Error('network error')));

      fixture.detectChanges();
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to load available chunkers', 'Dismiss', jasmine.any(Object));
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 2. OCR Status
  // ─────────────────────────────────────────────────────────────────────────

  describe('OCR status', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('isOcrAvailable() should return true when ocrEnabled and pipelineReady', () => {
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: true });
      expect(component.isOcrAvailable()).toBeTrue();
    });

    it('isOcrAvailable() should return false when ocrEnabled but pipeline not ready', () => {
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: false });
      expect(component.isOcrAvailable()).toBeFalse();
    });

    it('isOcrAvailable() should return false when ocrStatus is null', () => {
      component.ocrStatus = null;
      expect(component.isOcrAvailable()).toBeFalse();
    });

    it('getOcrStatusText() should return loading text when isLoadingOcrStatus', () => {
      component.isLoadingOcrStatus = true;
      expect(component.getOcrStatusText()).toBe('Checking OCR status...');
    });

    it('getOcrStatusText() should return error text when ocrStatusError is set', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = 'Connection refused';
      expect(component.getOcrStatusText()).toBe('OCR Error: Connection refused');
    });

    it('getOcrStatusText() should return unknown when ocrStatus is null', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = null;
      expect(component.getOcrStatusText()).toBe('OCR status unknown');
    });

    it('getOcrStatusText() should return disabled message when ocrEnabled=false', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: false });
      expect(component.getOcrStatusText()).toBe('OCR is disabled');
    });

    it('getOcrStatusText() should return pipeline not ready message', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: false });
      expect(component.getOcrStatusText()).toBe('OCR pipeline not ready - models may need to be loaded');
    });

    it('getOcrStatusText() should return available message when fully ready', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: true });
      expect(component.getOcrStatusText()).toBe('OCR is available');
    });

    it('getOcrStatusColor() should return accent when loading', () => {
      component.isLoadingOcrStatus = true;
      expect(component.getOcrStatusColor()).toBe('accent');
    });

    it('getOcrStatusColor() should return warn when error', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = 'error';
      expect(component.getOcrStatusColor()).toBe('warn');
    });

    it('getOcrStatusColor() should return primary when available', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: true });
      expect(component.getOcrStatusColor()).toBe('primary');
    });

    it('getOcrStatusIcon() should return hourglass_empty when loading', () => {
      component.isLoadingOcrStatus = true;
      expect(component.getOcrStatusIcon()).toBe('hourglass_empty');
    });

    it('getOcrStatusIcon() should return check_circle when OCR available', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: true });
      expect(component.getOcrStatusIcon()).toBe('check_circle');
    });

    it('getLoadedOcrModelCount() should count models with isLoaded=true', () => {
      component.ocrModels = [
        { modelId: 'm1', isLoaded: true } as OcrModelInfo,
        { modelId: 'm2', isLoaded: false } as OcrModelInfo,
        { modelId: 'm3', isLoaded: true } as OcrModelInfo
      ];
      expect(component.getLoadedOcrModelCount()).toBe(2);
    });

    it('toggleOcrStatusDetails() should toggle showOcrStatusDetails', () => {
      expect(component.showOcrStatusDetails).toBeFalse();
      component.toggleOcrStatusDetails();
      expect(component.showOcrStatusDetails).toBeTrue();
      component.toggleOcrStatusDetails();
      expect(component.showOcrStatusDetails).toBeFalse();
    });

    it('loadOcrStatus() should handle getStatus error and set fallback status', fakeAsync(() => {
      ocrServiceSpy.getStatus.and.returnValue(throwError(() => new Error('OCR unavailable')));
      ocrServiceSpy.getConfig.and.returnValue(of({} as OcrConfig));
      ocrServiceSpy.getModels.and.returnValue(of([]));

      component.loadOcrStatus();
      tick();

      expect(component.ocrStatus).toEqual(jasmine.objectContaining({ ocrEnabled: false, pipelineReady: false }));
      expect(component.isLoadingOcrStatus).toBeFalse();
      expect(component.ocrStatusError).toBeTruthy();
    }));

    it('refreshOcrStatus() should call loadOcrStatus', () => {
      spyOn(component, 'loadOcrStatus');
      component.refreshOcrStatus();
      expect(component.loadOcrStatus).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 3. OCR file checks
  // ─────────────────────────────────────────────────────────────────────────

  describe('OCR file extension checks', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('fileRequiresOcr() should return true for image extensions', () => {
      expect(component.fileRequiresOcr('photo.png')).toBeTrue();
      expect(component.fileRequiresOcr('scan.JPG')).toBeTrue();
      expect(component.fileRequiresOcr('image.tiff')).toBeTrue();
    });

    it('fileRequiresOcr() should return false for document extensions', () => {
      expect(component.fileRequiresOcr('doc.pdf')).toBeFalse();
      expect(component.fileRequiresOcr('report.docx')).toBeFalse();
    });

    it('fileMayBenefitFromOcr() should return true for pdf', () => {
      expect(component.fileMayBenefitFromOcr('report.pdf')).toBeTrue();
    });

    it('fileMayBenefitFromOcr() should return false for non-pdf', () => {
      expect(component.fileMayBenefitFromOcr('document.docx')).toBeFalse();
    });

    it('hasFilesRequiringOcr() should return true when image files are selected', () => {
      component.selectedFiles = [makeFile('scan.png', 'image/png')];
      expect(component.hasFilesRequiringOcr()).toBeTrue();
    });

    it('hasFilesRequiringOcr() should return false when no image files selected', () => {
      component.selectedFiles = [makeFile('report.pdf')];
      expect(component.hasFilesRequiringOcr()).toBeFalse();
    });

    it('hasFilesThatMayBenefitFromOcr() should return true when pdf is selected', () => {
      component.selectedFiles = [makeFile('report.pdf')];
      expect(component.hasFilesThatMayBenefitFromOcr()).toBeTrue();
    });

    it('shouldShowOcrWarning() should return false when no files selected', () => {
      component.selectedFiles = [];
      expect(component.shouldShowOcrWarning()).toBeFalse();
    });

    it('shouldShowOcrWarning() should return false when OCR is available', () => {
      component.selectedFiles = [makeFile('scan.png', 'image/png')];
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: true });
      expect(component.shouldShowOcrWarning()).toBeFalse();
    });

    it('shouldShowOcrWarning() should return true when image file selected and OCR unavailable', () => {
      component.selectedFiles = [makeFile('scan.png', 'image/png')];
      component.ocrStatus = makeOcrStatus({ ocrEnabled: false, pipelineReady: false });
      expect(component.shouldShowOcrWarning()).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 4. Source type selection
  // ─────────────────────────────────────────────────────────────────────────

  describe('Source type selection', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('selectSourceType() should change sourceType and clear results', () => {
      component.analysisResult = makeAnalysisResult();
      component.selectSourceType('url');
      expect(component.sourceType).toBe('url');
      expect(component.analysisResult).toBeNull();
    });

    it('selectSourceType() should support all source types', () => {
      const types: Array<'file' | 'url' | 'path' | 'text'> = ['file', 'url', 'path', 'text'];
      for (const t of types) {
        component.selectSourceType(t);
        expect(component.sourceType).toBe(t);
      }
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 5. File selection
  // ─────────────────────────────────────────────────────────────────────────

  describe('File selection', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('onFileSelectedChange() should set selectedFile and selectedFiles', () => {
      const file = makeFile('test.pdf');
      const mockEvent = { target: { files: [file] } } as any;
      component.onFileSelectedChange(mockEvent);
      expect(component.selectedFile).toBe(file);
      expect(component.selectedFiles.length).toBe(1);
      expect(component.fileErrorMessage).toBeNull();
    });

    it('onFileSelectedChange() should clear selection when no files', () => {
      component.selectedFile = makeFile('old.pdf');
      const mockEvent = { target: { files: [] } } as any;
      component.onFileSelectedChange(mockEvent);
      expect(component.selectedFile).toBeNull();
      expect(component.selectedFiles.length).toBe(0);
    });

    it('removeFile() should remove file at given index', () => {
      const f1 = makeFile('a.pdf');
      const f2 = makeFile('b.pdf');
      const f3 = makeFile('c.pdf');
      component.selectedFiles = [f1, f2, f3];
      component.removeFile(1);
      expect(component.selectedFiles.length).toBe(2);
      expect(component.selectedFiles[0]).toBe(f1);
      expect(component.selectedFiles[1]).toBe(f3);
    });

    it('removeFile() should set selectedFile to first remaining file', () => {
      const f1 = makeFile('a.pdf');
      const f2 = makeFile('b.pdf');
      component.selectedFiles = [f1, f2];
      component.removeFile(0);
      expect(component.selectedFile).toBe(f2);
    });

    it('removeFile() should set selectedFile to null when last file removed', () => {
      component.selectedFiles = [makeFile('only.pdf')];
      component.selectedFile = component.selectedFiles[0];
      component.removeFile(0);
      expect(component.selectedFile).toBeNull();
    });

    it('getFileIcon() should return picture_as_pdf for pdf files', () => {
      expect(component.getFileIcon('report.pdf')).toBe('picture_as_pdf');
    });

    it('getFileIcon() should return description for docx files', () => {
      expect(component.getFileIcon('doc.docx')).toBe('description');
    });

    it('getFileIcon() should return insert_drive_file for unknown extensions', () => {
      expect(component.getFileIcon('data.xyz')).toBe('insert_drive_file');
    });

    it('getFileIcon() should return email for eml files', () => {
      expect(component.getFileIcon('message.eml')).toBe('email');
    });

    it('formatFileSize() should return bytes for small files', () => {
      expect(component.formatFileSize(512)).toBe('512 B');
    });

    it('formatFileSize() should return KB for kilobyte files', () => {
      expect(component.formatFileSize(2048)).toBe('2.0 KB');
    });

    it('formatFileSize() should return MB for megabyte files', () => {
      expect(component.formatFileSize(3 * 1024 * 1024)).toBe('3.0 MB');
    });

    it('getTotalFileSize() should sum sizes of all selected files', () => {
      component.selectedFiles = [
        makeFile('a.pdf', 'application/pdf', 1024),
        makeFile('b.pdf', 'application/pdf', 2048)
      ];
      // 3072 bytes = 3.0 KB
      expect(component.getTotalFileSize()).toBe('3.0 KB');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 6. Drag and drop
  // ─────────────────────────────────────────────────────────────────────────

  describe('Drag and drop', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('onDragOver() should set isDragOver=true when not busy', () => {
      const event = { preventDefault: () => {}, stopPropagation: () => {} } as any;
      component.isUploading = false;
      component.isAnalyzing = false;
      component.onDragOver(event);
      expect(component.isDragOver).toBeTrue();
    });

    it('onDragOver() should NOT set isDragOver=true when uploading', () => {
      const event = { preventDefault: () => {}, stopPropagation: () => {} } as any;
      component.isUploading = true;
      component.onDragOver(event);
      expect(component.isDragOver).toBeFalse();
    });

    it('onDragLeave() should set isDragOver=false', () => {
      component.isDragOver = true;
      const event = { preventDefault: () => {}, stopPropagation: () => {} } as any;
      component.onDragLeave(event);
      expect(component.isDragOver).toBeFalse();
    });

    it('onDrop() should add dropped files to selectedFiles', () => {
      const file = makeFile('dropped.pdf');
      const event = {
        preventDefault: () => {},
        stopPropagation: () => {},
        dataTransfer: { files: [file] }
      } as any;
      component.onDrop(event);
      expect(component.selectedFiles.length).toBe(1);
      expect(component.selectedFile).toBe(file);
      expect(component.isDragOver).toBeFalse();
    });

    it('onDrop() should append to existing files', () => {
      component.selectedFiles = [makeFile('existing.pdf')];
      const newFile = makeFile('new.txt', 'text/plain');
      const event = {
        preventDefault: () => {},
        stopPropagation: () => {},
        dataTransfer: { files: [newFile] }
      } as any;
      component.onDrop(event);
      expect(component.selectedFiles.length).toBe(2);
    });

    it('onDrop() should do nothing when uploading', () => {
      component.isUploading = true;
      const event = {
        preventDefault: () => {},
        stopPropagation: () => {},
        dataTransfer: { files: [makeFile('file.pdf')] }
      } as any;
      component.onDrop(event);
      expect(component.selectedFiles.length).toBe(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 7. Chunking configuration
  // ─────────────────────────────────────────────────────────────────────────

  describe('Chunking configuration', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('toggleChunkingSection() should toggle showChunkingOptions', () => {
      const initial = component.showChunkingOptions;
      component.toggleChunkingSection();
      expect(component.showChunkingOptions).toBe(!initial);
    });

    it('toggleAdvancedOptions() should toggle showAdvancedOptions', () => {
      expect(component.showAdvancedOptions).toBeFalse();
      component.toggleAdvancedOptions();
      expect(component.showAdvancedOptions).toBeTrue();
    });

    it('toggleTokenizerSection() should toggle showTokenizerOptions', () => {
      expect(component.showTokenizerOptions).toBeFalse();
      component.toggleTokenizerSection();
      expect(component.showTokenizerOptions).toBeTrue();
    });

    it('selectStrategy() should update chunkingConfig strategy', () => {
      component.selectStrategy('sentence' as any);
      expect(component.chunkingConfig.strategy).toBe('sentence');
      expect(component.chunkingConfig.useCustomSettings).toBeTrue();
      expect(component.selectedPreset).toBe('custom');
    });

    it('selectStrategy() should not change when uploading', () => {
      component.isUploading = true;
      component.selectStrategy('token' as any);
      expect(component.chunkingConfig.strategy).not.toBe('token');
    });

    it('selectStrategy() should set useCustomSettings=false for auto strategy', () => {
      component.selectStrategy('auto' as any);
      expect(component.chunkingConfig.useCustomSettings).toBeFalse();
    });

    it('onChunkingOptionChange() should set useCustomSettings=true and preset to custom', () => {
      component.onChunkingOptionChange();
      expect(component.chunkingConfig.useCustomSettings).toBeTrue();
      expect(component.selectedPreset).toBe('custom');
    });

    it('getActivePresetName() should return Custom when selectedPreset is custom', () => {
      component.selectedPreset = 'custom';
      expect(component.getActivePresetName()).toBe('Custom');
    });

    it('applyPreset() should update chunkingConfig from preset', () => {
      // Find a real preset
      const preset = component.chunkingPresets[0];
      component.applyPreset(preset.id);
      expect(component.selectedPreset).toBe(preset.id);
    });

    it('onTokenizerChange() should update selectedTokenizer', () => {
      component.onTokenizerChange('default');
      expect(component.selectedTokenizer).toBe('default');
    });

    it('getSelectedTokenizerName() should return tokenizer name', () => {
      component.selectedTokenizer = 'default';
      const name = component.getSelectedTokenizerName();
      expect(typeof name).toBe('string');
      expect(name.length).toBeGreaterThan(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 8. canRunTest()
  // ─────────────────────────────────────────────────────────────────────────

  describe('canRunTest()', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('should return false when uploading', () => {
      component.isUploading = true;
      expect(component.canRunTest()).toBeFalse();
    });

    it('should return false when analyzing', () => {
      component.isAnalyzing = true;
      expect(component.canRunTest()).toBeFalse();
    });

    it('should return false for file type when no files selected', () => {
      component.sourceType = 'file';
      component.selectedFiles = [];
      expect(component.canRunTest()).toBeFalse();
    });

    it('should return true for file type when files are selected', () => {
      component.sourceType = 'file';
      component.selectedFiles = [makeFile('test.pdf')];
      expect(component.canRunTest()).toBeTrue();
    });

    it('should return false for url type when urlInput is empty', () => {
      component.sourceType = 'url';
      component.urlInput = '';
      expect(component.canRunTest()).toBeFalse();
    });

    it('should return true for url type when urlInput has content', () => {
      component.sourceType = 'url';
      component.urlInput = 'https://example.com/doc.html';
      expect(component.canRunTest()).toBeTrue();
    });

    it('should return false for path type when pathInput is empty', () => {
      component.sourceType = 'path';
      component.pathInput = '';
      expect(component.canRunTest()).toBeFalse();
    });

    it('should return true for path type when pathInput has content', () => {
      component.sourceType = 'path';
      component.pathInput = '/data/documents';
      expect(component.canRunTest()).toBeTrue();
    });

    it('should return false for text type when textInput is blank', () => {
      component.sourceType = 'text';
      component.textInput = '   ';
      expect(component.canRunTest()).toBeFalse();
    });

    it('should return true for text type when textInput has content', () => {
      component.sourceType = 'text';
      component.textInput = 'Some content to analyze';
      expect(component.canRunTest()).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 9. Text input utilities
  // ─────────────────────────────────────────────────────────────────────────

  describe('Text input utilities', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('getTextCharacterCount() should return length of textInput', () => {
      component.textInput = 'Hello world';
      expect(component.getTextCharacterCount()).toBe(11);
    });

    it('getTextCharacterCount() should return 0 when textInput is empty', () => {
      component.textInput = '';
      expect(component.getTextCharacterCount()).toBe(0);
    });

    it('getTextWordCount() should count words in textInput', () => {
      component.textInput = 'Hello world foo bar';
      expect(component.getTextWordCount()).toBe(4);
    });

    it('getTextWordCount() should return 0 for empty input', () => {
      component.textInput = '';
      expect(component.getTextWordCount()).toBe(0);
    });

    it('getTextWordCount() should ignore leading/trailing whitespace', () => {
      component.textInput = '  one two  three  ';
      expect(component.getTextWordCount()).toBe(3);
    });

    it('clearTextInput() should clear textInput and results', () => {
      component.textInput = 'Some content';
      component.analysisResult = makeAnalysisResult();
      component.clearTextInput();
      expect(component.textInput).toBe('');
      expect(component.analysisResult).toBeNull();
      expect(component.clipboardError).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 10. Results display methods
  // ─────────────────────────────────────────────────────────────────────────

  describe('Results display methods', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('clearResults() should reset all result state', () => {
      component.analysisResult = makeAnalysisResult();
      component.selectedDocumentIndex = 2;
      component.selectedChunkIndex = 1 as unknown as null;
      component.chunkPageIndex = 3;
      component.displayedChunks = [{ id: 'c1', text: 'text', contentLength: 4, chunkIndex: 0, metadata: {} }];

      component.clearResults();

      expect(component.analysisResult).toBeNull();
      expect(component.selectedDocumentIndex).toBe(0);
      expect(component.selectedChunkIndex).toBeNull();
      expect(component.chunkPageIndex).toBe(0);
      expect(component.displayedChunks.length).toBe(0);
    });

    it('selectDocument() should update selectedDocumentIndex', () => {
      component.selectDocument(3);
      expect(component.selectedDocumentIndex).toBe(3);
    });

    it('selectChunk() should set selectedChunkIndex to index if different', () => {
      component.selectedChunkIndex = null;
      component.selectChunk(2);
      expect(component.selectedChunkIndex).toBe(2 as unknown as null);
    });

    it('selectChunk() should toggle off selectedChunkIndex if same index', () => {
      component.selectedChunkIndex = 2 as unknown as null;
      component.selectChunk(2);
      expect(component.selectedChunkIndex).toBeNull();
    });

    it('onChunkPageChange() should update page state and refresh displayedChunks', () => {
      component.analysisResult = makeAnalysisResult();
      const event: PageEvent = { pageIndex: 1, pageSize: 5, length: 10 };
      component.onChunkPageChange(event);
      expect(component.chunkPageSize).toBe(5);
      expect(component.chunkPageIndex).toBe(1);
    });

    it('updateDisplayedChunks() should return empty array when no analysisResult', () => {
      component.analysisResult = null;
      component.updateDisplayedChunks();
      expect(component.displayedChunks.length).toBe(0);
    });

    it('updateDisplayedChunks() should paginate chunks correctly', () => {
      const chunks = Array.from({ length: 15 }, (_, i) => ({
        id: `c${i}`, text: `Chunk ${i}`, contentLength: 7, chunkIndex: i, metadata: {}
      }));
      component.analysisResult = makeAnalysisResult({ chunks });
      component.chunkPageIndex = 1;
      component.chunkPageSize = 10;
      component.updateDisplayedChunks();
      expect(component.displayedChunks.length).toBe(5); // second page has 5
    });

    it('updateDisplayedChunks() should return first page of chunks', () => {
      const chunks = Array.from({ length: 25 }, (_, i) => ({
        id: `c${i}`, text: `Chunk ${i}`, contentLength: 7, chunkIndex: i, metadata: {}
      }));
      component.analysisResult = makeAnalysisResult({ chunks });
      component.chunkPageIndex = 0;
      component.chunkPageSize = 10;
      component.updateDisplayedChunks();
      expect(component.displayedChunks.length).toBe(10);
      expect(component.displayedChunks[0].id).toBe('c0');
    });

    it('getSelectedDocument() should return the document at selectedDocumentIndex', () => {
      component.analysisResult = makeAnalysisResult();
      component.selectedDocumentIndex = 0;
      const doc = component.getSelectedDocument();
      expect(doc).not.toBeNull();
      expect(doc!.id).toBe('doc-1');
    });

    it('getSelectedDocument() should return null when no analysisResult', () => {
      component.analysisResult = null;
      expect(component.getSelectedDocument()).toBeNull();
    });

    it('getDocumentPreview() should return full text for short documents', () => {
      const doc: any = { id: 'd1', text: 'Short', contentLength: 5, hasContent: true, metadata: {}, contentStats: {} };
      expect(component.getDocumentPreview(doc)).toBe('Short');
    });

    it('getDocumentPreview() should truncate long document text at 300 chars', () => {
      const longText = 'x'.repeat(400);
      const doc: any = { id: 'd1', text: longText, contentLength: 400, hasContent: true, metadata: {}, contentStats: {} };
      const preview = component.getDocumentPreview(doc);
      expect(preview.length).toBe(303); // 300 + '...'
      expect(preview.endsWith('...')).toBeTrue();
    });

    it('getDocumentPreview() should return (No content) when text is empty', () => {
      const doc: any = { id: 'd1', text: '', contentLength: 0, hasContent: false, metadata: {}, contentStats: {} };
      expect(component.getDocumentPreview(doc)).toBe('(No content)');
    });

    it('getChunkPreview() should truncate chunk text at 200 chars', () => {
      const longText = 'y'.repeat(300);
      const chunk: any = { id: 'c1', text: longText, contentLength: 300, chunkIndex: 0, metadata: {} };
      const preview = component.getChunkPreview(chunk);
      expect(preview.length).toBe(203);
      expect(preview.endsWith('...')).toBeTrue();
    });

    it('getChunkPreview() should return (No content) for empty chunk', () => {
      const chunk: any = { id: 'c1', text: '', contentLength: 0, chunkIndex: 0, metadata: {} };
      expect(component.getChunkPreview(chunk)).toBe('(No content)');
    });

    it('getLoaderClass() should return loader-noop for noop loaders', () => {
      expect(component.getLoaderClass({ name: 'noop', className: 'NoOpLoader', isNoOp: true, supportsFile: false, supportReason: '' } as any)).toBe('loader-noop');
    });

    it('getLoaderClass() should return loader-supports for supported loaders', () => {
      expect(component.getLoaderClass({ name: 'pdf', className: 'PdfLoader', isNoOp: false, supportsFile: true, supportReason: 'Supports PDF' } as any)).toBe('loader-supports');
    });

    it('getLoaderClass() should return loader-unsupported for unsupported loaders', () => {
      expect(component.getLoaderClass({ name: 'docx', className: 'DocxLoader', isNoOp: false, supportsFile: false, supportReason: '' } as any)).toBe('loader-unsupported');
    });

    it('getChunkerClass() should return chunker-noop for noop chunkers', () => {
      expect(component.getChunkerClass({ name: 'noop', className: 'NoOpChunker', isNoOp: true, reason: '' } as any)).toBe('chunker-noop');
    });

    it('getChunkerClass() should return chunker-active for active chunkers', () => {
      expect(component.getChunkerClass({ name: 'sentence', className: 'SentenceChunker', isNoOp: false, reason: '' } as any)).toBe('chunker-active');
    });

    it('toggleChunkViewMode() should switch between cards and list', () => {
      expect(component.chunkViewMode).toBe('cards');
      component.toggleChunkViewMode();
      expect(component.chunkViewMode).toBe('list');
      component.toggleChunkViewMode();
      expect(component.chunkViewMode).toBe('cards');
    });

    it('toggleChunkExpanded() should toggle expanded index', () => {
      component.expandedChunkIndex = null;
      component.toggleChunkExpanded(2);
      expect(component.expandedChunkIndex).toBe(2 as unknown as null);
      component.toggleChunkExpanded(2);
      expect(component.expandedChunkIndex).toBeNull();
    });

    it('toggleChunkExpanded() should switch to new index', () => {
      component.expandedChunkIndex = 1 as unknown as null;
      component.toggleChunkExpanded(3);
      expect(component.expandedChunkIndex).toBe(3 as unknown as null);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 11. Utility methods
  // ─────────────────────────────────────────────────────────────────────────

  describe('Utility methods', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('getMetadataKeys() should return keys of metadata object', () => {
      const keys = component.getMetadataKeys({ source: 'test.pdf', page: 1 });
      expect(keys).toContain('source');
      expect(keys).toContain('page');
    });

    it('getMetadataKeys() should return empty array for null metadata', () => {
      expect(component.getMetadataKeys(null as any)).toEqual([]);
    });

    it('formatMetadataValue() should return "null" for null values', () => {
      expect(component.formatMetadataValue(null)).toBe('null');
    });

    it('formatMetadataValue() should JSON.stringify objects', () => {
      expect(component.formatMetadataValue({ a: 1 })).toBe('{"a":1}');
    });

    it('formatMetadataValue() should convert numbers to string', () => {
      expect(component.formatMetadataValue(42)).toBe('42');
    });

    it('formatMetadataValue() should return string as-is', () => {
      expect(component.formatMetadataValue('hello')).toBe('hello');
    });

    it('copyToClipboard() should call navigator.clipboard.writeText', fakeAsync(() => {
      const origClipboard = navigator.clipboard;
      const clipSpy = jasmine.createSpyObj('Clipboard', ['writeText']);
      clipSpy.writeText.and.returnValue(Promise.resolve());
      Object.defineProperty(navigator, 'clipboard', { value: clipSpy, configurable: true });
      component.copyToClipboard('test text');
      tick();
      expect(clipSpy.writeText).toHaveBeenCalledWith('test text');
      Object.defineProperty(navigator, 'clipboard', { value: origClipboard, configurable: true });
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 12. Composite PDF loader
  // ─────────────────────────────────────────────────────────────────────────

  describe('Composite PDF loader', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('hasSelectedPdfFiles() should return true when PDF files are selected', () => {
      component.selectedFiles = [makeFile('report.pdf')];
      expect(component.hasSelectedPdfFiles()).toBeTrue();
    });

    it('hasSelectedPdfFiles() should return false when no PDF files selected', () => {
      component.selectedFiles = [makeFile('doc.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document')];
      expect(component.hasSelectedPdfFiles()).toBeFalse();
    });

    it('hasSelectedPdfFiles() should return false when selectedFiles is empty', () => {
      component.selectedFiles = [];
      expect(component.hasSelectedPdfFiles()).toBeFalse();
    });

    it('toggleCompositePdfLoader() should toggle useCompositePdfLoader', () => {
      expect(component.useCompositePdfLoader).toBeFalse();
      component.toggleCompositePdfLoader();
      expect(component.useCompositePdfLoader).toBeTrue();
      component.toggleCompositePdfLoader();
      expect(component.useCompositePdfLoader).toBeFalse();
    });

    it('toggleCompositeLoaderComparison() should toggle showCompositeLoaderComparison', () => {
      const initial = component.showCompositeLoaderComparison;
      component.toggleCompositeLoaderComparison();
      expect(component.showCompositeLoaderComparison).toBe(!initial);
    });

    it('hasCompositeLoaderComparison() should return false when no analysisResult', () => {
      component.analysisResult = null;
      expect(component.hasCompositeLoaderComparison()).toBeFalse();
    });

    it('hasCompositeLoaderComparison() should return true when compositeLoaderUsed is true', () => {
      component.analysisResult = makeAnalysisResult({
        compositeLoaderComparison: {
          compositeLoaderUsed: true,
          selectedLoader: 'pdf',
          selectionReason: 'Most text',
          loadersCompared: 2,
          loaderStats: {}
        }
      });
      expect(component.hasCompositeLoaderComparison()).toBeTrue();
    });

    it('getComparedLoaders() should return empty array when no comparison', () => {
      component.analysisResult = null;
      expect(component.getComparedLoaders()).toEqual([]);
    });

    it('getComparedLoaders() should return loader names from loaderStats', () => {
      component.analysisResult = makeAnalysisResult({
        compositeLoaderComparison: {
          compositeLoaderUsed: true,
          selectedLoader: 'PdfLoader',
          selectionReason: 'Best',
          loadersCompared: 2,
          loaderStats: {
            'PdfLoader': { loaderName: 'PdfLoader', loaderClassName: 'PdfLoader', documentCount: 3, totalCharacters: 1000, totalWords: 100, hadError: false, processingTimeMs: 50 },
            'TikaLoader': { loaderName: 'TikaLoader', loaderClassName: 'TikaLoader', documentCount: 2, totalCharacters: 800, totalWords: 80, hadError: false, processingTimeMs: 100 }
          }
        }
      });
      const loaders = component.getComparedLoaders();
      expect(loaders.length).toBe(2);
      expect(loaders).toContain('PdfLoader');
      expect(loaders).toContain('TikaLoader');
    });

    it('getLoaderStats() should return null when no analysisResult', () => {
      component.analysisResult = null;
      expect(component.getLoaderStats('PdfLoader')).toBeNull();
    });

    it('isSelectedLoader() should return true for the selected loader', () => {
      component.analysisResult = makeAnalysisResult({
        compositeLoaderComparison: {
          compositeLoaderUsed: true,
          selectedLoader: 'PdfLoader',
          selectionReason: 'Best',
          loadersCompared: 1,
          loaderStats: {}
        }
      });
      expect(component.isSelectedLoader('PdfLoader')).toBeTrue();
      expect(component.isSelectedLoader('TikaLoader')).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 13. runTest() dispatching
  // ─────────────────────────────────────────────────────────────────────────

  describe('runTest() dispatching', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('runTest() should do nothing when canRunTest() returns false', async () => {
      component.sourceType = 'file';
      component.selectedFiles = [];
      await component.runTest();
      expect(httpClientSpy.post).not.toHaveBeenCalled();
    });

    it('runTest() for url sourceType should show not-implemented snackbar', async () => {
      component.sourceType = 'url';
      component.urlInput = 'https://example.com/doc.pdf';
      await component.runTest();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'URL testing not yet implemented. Please use file upload.',
        'Dismiss',
        jasmine.any(Object)
      );
    });

    it('runTest() for path sourceType should analyze server path', fakeAsync(async () => {
      component.sourceType = 'path';
      component.pathInput = '/data/docs/report.pdf';
      component.chunkingConfig.strategy = 'auto';
      component.selectedLoader = '';

      const mockResult = makeAnalysisResult({ fileName: 'report.pdf' });
      httpClientSpy.post.and.returnValue(of(mockResult));

      await component.runTest();
      tick();

      expect(httpClientSpy.post).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 14. mergeBackendChunkers
  // ─────────────────────────────────────────────────────────────────────────

  describe('mergeBackendChunkers()', () => {
    it('should add new backend chunkers not in CHUNKER_STRATEGIES', fakeAsync(() => {
      const chunkers: ChunkerInfo[] = [
        { name: 'my-custom-chunker', className: 'ai.MyChunker' }
      ];
      documentServiceSpy.getAvailableChunkers.and.returnValue(of(chunkers));

      fixture.detectChanges();
      tick();

      const found = component.chunkerStrategies.find(s => (s.id as string) === 'my-custom-chunker');
      expect(found).toBeTruthy();
      expect(found?.name).toBe('My Custom Chunker');
    }));

    it('should skip noop chunkers from backend', fakeAsync(() => {
      const chunkers: ChunkerInfo[] = [
        { name: 'noop-chunker', className: 'NoOpChunker' }
      ];
      documentServiceSpy.getAvailableChunkers.and.returnValue(of(chunkers));
      const initialCount = component.chunkerStrategies.length;

      fixture.detectChanges();
      tick();

      expect(component.chunkerStrategies.length).toBe(initialCount);
    }));

    it('should skip backend chunkers already in CHUNKER_STRATEGIES', fakeAsync(() => {
      // 'auto' is typically in the static list
      const initialStrategies = [...component.chunkerStrategies];
      const existingId = initialStrategies[0].id;
      const chunkers: ChunkerInfo[] = [
        { name: existingId, className: 'ExistingClass' }
      ];
      documentServiceSpy.getAvailableChunkers.and.returnValue(of(chunkers));

      fixture.detectChanges();
      tick();

      const matchingStrategies = component.chunkerStrategies.filter(s => s.id === existingId);
      expect(matchingStrategies.length).toBe(1); // not duplicated
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 15. ngOnDestroy
  // ─────────────────────────────────────────────────────────────────────────

  describe('ngOnDestroy', () => {
    it('should unsubscribe all subscriptions on destroy', () => {
      fixture.detectChanges();
      const unsubSpy = spyOn((component as any).subscriptions, 'unsubscribe').and.callThrough();
      component.ngOnDestroy();
      expect(unsubSpy).toHaveBeenCalled();
    });
  });
});
