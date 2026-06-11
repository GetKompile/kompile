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
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { DocumentDebuggerComponent, DebugAnalysisResult, ChunkDebugInfo } from './document-debugger.component';
import { OcrService } from '../../../services/ocr.service';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatSortModule } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { OcrStatus, OcrConfig, OcrModelInfo } from '../../../models/ocr-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

function makeOcrStatus(overrides: Partial<OcrStatus> = {}): OcrStatus {
  return {
    ocrEnabled: true,
    pipelineReady: true,
    postProcessorAvailable: false,
    ...overrides
  };
}

function makeAnalysisResult(overrides: Partial<DebugAnalysisResult> = {}): DebugAnalysisResult {
  return {
    fileName: 'test.pdf',
    filePath: '/tmp/test.pdf',
    fileSize: 1024,
    availableLoaders: [],
    selectedLoader: null,
    loadedDocuments: [
      {
        id: 'doc-1',
        content: 'Hello World document content',
        contentLength: 28,
        hasContent: true,
        metadata: {},
        contentLines: ['Hello World document content'],
        contentStats: {}
      }
    ],
    availableChunkers: [],
    selectedChunker: null,
    chunks: [
      {
        id: 'chunk-0',
        content: 'Hello World',
        contentLength: 11,
        chunkIndex: 0,
        metadata: { content_type: 'text' }
      }
    ],
    processingStats: {},
    errorMessage: null,
    ...overrides
  };
}

function makeChunk(overrides: Partial<ChunkDebugInfo> = {}): ChunkDebugInfo {
  return {
    id: 'chunk-0',
    content: 'Some chunk content',
    contentLength: 18,
    chunkIndex: 0,
    metadata: {},
    ...overrides
  };
}

function makeFile(name: string, type = 'application/pdf'): File {
  return new File(['dummy content'], name, { type });
}

function createTestBed() {
  const ocrServiceSpy = jasmine.createSpyObj('OcrService', [
    'getStatus', 'getConfig', 'getModels'
  ]);
  const snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

  // Default return values
  ocrServiceSpy.getStatus.and.returnValue(of(makeOcrStatus()));
  ocrServiceSpy.getConfig.and.returnValue(of({} as OcrConfig));
  ocrServiceSpy.getModels.and.returnValue(of([] as OcrModelInfo[]));

  return {
    ocrServiceSpy,
    snackBarSpy,
    providers: [
      { provide: OcrService, useValue: ocrServiceSpy },
      { provide: MatSnackBar, useValue: snackBarSpy }
    ]
  };
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('DocumentDebuggerComponent', () => {
  let component: DocumentDebuggerComponent;
  let fixture: ComponentFixture<DocumentDebuggerComponent>;
  let httpMock: HttpTestingController;
  let spies: ReturnType<typeof createTestBed>;

  beforeEach(async () => {
    spies = createTestBed();

    await TestBed.configureTestingModule({
      imports: [HttpClientTestingModule, NoopAnimationsModule, MatSortModule, MatTableModule],
      declarations: [DocumentDebuggerComponent],
      providers: spies.providers,
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DocumentDebuggerComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. COMPONENT CREATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Component creation', () => {
    it('should create the component', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should initialize with default state', () => {
      expect(component.selectedFileName).toBe('');
      expect(component.isAnalyzing).toBeFalse();
      expect(component.isUploading).toBeFalse();
      expect(component.analysisResult).toBeNull();
      expect(component.selectedFile).toBeNull();
      expect(component.expandedChunkIndex).toBeNull();
      expect(component.analysisError).toBeNull();
    });

    it('should initialize OCR state', () => {
      expect(component.ocrStatus).toBeNull();
      expect(component.ocrModels).toEqual([]);
      expect(component.isLoadingOcrStatus).toBeFalse();
    });

    it('should call loadOcrStatus on ngOnInit', () => {
      fixture.detectChanges(); // triggers ngOnInit
      expect(spies.ocrServiceSpy.getStatus).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. analyzeFile()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('analyzeFile()', () => {
    beforeEach(() => {
      fixture.detectChanges(); // ngOnInit → loadOcrStatus
      component.selectedFileName = 'test.pdf';
    });

    it('should POST to /api/documents/debug/analyze-file with fileName param', () => {
      component.analyzeFile();

      const req = httpMock.expectOne(r =>
        r.url.includes('/documents/debug/analyze-file') && r.params.get('fileName') === 'test.pdf'
      );
      expect(req.request.method).toBe('POST');
      req.flush(makeAnalysisResult());
    });

    it('should set analysisResult on success', () => {
      const result = makeAnalysisResult();
      component.analyzeFile();

      const req = httpMock.expectOne(r => r.url.includes('/documents/debug/analyze-file'));
      req.flush(result);

      expect(component.analysisResult).toEqual(jasmine.objectContaining({ fileName: 'test.pdf' }));
      expect(component.isAnalyzing).toBeFalse();
    });

    it('should show snackbar on success', () => {
      component.analyzeFile();

      const req = httpMock.expectOne(r => r.url.includes('/documents/debug/analyze-file'));
      req.flush(makeAnalysisResult());

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Analysis successful/),
        'Close',
        jasmine.any(Object)
      );
    });

    it('should show error snackbar when result has errorMessage', () => {
      const result = makeAnalysisResult({ errorMessage: 'Loader failed' });
      component.analyzeFile();

      const req = httpMock.expectOne(r => r.url.includes('/documents/debug/analyze-file'));
      req.flush(result);

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Loader failed/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });

    it('should set analysisError on HTTP error', () => {
      component.analyzeFile();

      const req = httpMock.expectOne(r => r.url.includes('/documents/debug/analyze-file'));
      req.flush({ message: 'Server error' }, { status: 500, statusText: 'Server Error' });

      expect(component.analysisError).toBeTruthy();
      expect(component.isAnalyzing).toBeFalse();
    });

    it('should not send request when selectedFileName is empty', () => {
      component.selectedFileName = '';
      component.analyzeFile();

      httpMock.expectNone(r => r.url.includes('/documents/debug/analyze-file'));
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Please select a file for analysis/),
        'Close',
        jasmine.any(Object)
      );
    });

    it('should clear previous result before new analysis', () => {
      component.analysisResult = makeAnalysisResult();
      component.analysisError = 'old error';
      component.analyzeFile();

      expect(component.analysisResult).toBeNull();
      expect(component.analysisError).toBeNull();

      const req = httpMock.expectOne(r => r.url.includes('/documents/debug/analyze-file'));
      req.flush(makeAnalysisResult());
    });

    it('should populate chunksDataSource from result chunks', () => {
      const result = makeAnalysisResult({
        chunks: [
          makeChunk({ chunkIndex: 0, content: 'chunk A' }),
          makeChunk({ chunkIndex: 1, content: 'chunk B', id: 'chunk-1' })
        ]
      });
      component.analyzeFile();

      const req = httpMock.expectOne(r => r.url.includes('/documents/debug/analyze-file'));
      req.flush(result);

      expect(component.chunksDataSource.data.length).toBe(2);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. uploadTestFile()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('uploadTestFile()', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should POST FormData to /api/documents/debug/test-upload', () => {
      component.selectedFile = makeFile('report.pdf');
      component.uploadTestFile();

      const req = httpMock.expectOne(r => r.url.includes('/documents/debug/test-upload'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body instanceof FormData).toBeTrue();
      req.flush({ fileName: 'report.pdf' });
    });

    it('should set selectedFileName from response on success', () => {
      component.selectedFile = makeFile('report.pdf');
      component.uploadTestFile();

      const req = httpMock.expectOne(r => r.url.includes('/documents/debug/test-upload'));
      req.flush({ fileName: 'report.pdf' });

      expect(component.selectedFileName).toBe('report.pdf');
      expect(component.isUploading).toBeFalse();
    });

    it('should clear selectedFile after successful upload', () => {
      component.selectedFile = makeFile('report.pdf');
      component.uploadTestFile();

      const req = httpMock.expectOne(r => r.url.includes('/documents/debug/test-upload'));
      req.flush({ fileName: 'report.pdf' });

      expect(component.selectedFile).toBeNull();
    });

    it('should show error snackbar on upload failure', () => {
      component.selectedFile = makeFile('report.pdf');
      component.uploadTestFile();

      const req = httpMock.expectOne(r => r.url.includes('/documents/debug/test-upload'));
      req.flush({ error: 'Too large' }, { status: 413, statusText: 'Payload Too Large' });

      expect(component.isUploading).toBeFalse();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Upload failed/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });

    it('should show warning when no file selected', () => {
      component.selectedFile = null;
      component.uploadTestFile();

      httpMock.expectNone(r => r.url.includes('/documents/debug/test-upload'));
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Please select a file to upload/),
        'Close',
        jasmine.any(Object)
      );
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. onFileSelected()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('onFileSelected()', () => {
    it('should set selectedFile when a file is chosen', () => {
      fixture.detectChanges();
      const file = makeFile('document.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document');
      const dataTransfer = new DataTransfer();
      dataTransfer.items.add(file);

      const input = document.createElement('input');
      input.type = 'file';
      Object.defineProperty(input, 'files', { value: dataTransfer.files });

      const event = { target: input } as unknown as Event;
      component.onFileSelected(event);

      expect(component.selectedFile).toBe(file);
    });

    it('should not set selectedFile when no file is present', () => {
      fixture.detectChanges();
      component.selectedFile = null;
      const input = document.createElement('input');
      input.type = 'file';
      // files is empty by default

      const event = { target: input } as unknown as Event;
      component.onFileSelected(event);

      expect(component.selectedFile).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. retryAnalysis()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('retryAnalysis()', () => {
    it('should clear analysisError before calling analyzeFile', () => {
      fixture.detectChanges();
      component.selectedFileName = 'test.pdf';
      component.analysisError = 'Previous error';

      component.retryAnalysis();

      expect(component.analysisError).toBeNull();

      const req = httpMock.expectOne(r => r.url.includes('/documents/debug/analyze-file'));
      req.flush(makeAnalysisResult());
    });

    it('should call analyzeFile (POST request sent)', () => {
      fixture.detectChanges();
      component.selectedFileName = 'retry.pdf';
      component.retryAnalysis();

      const req = httpMock.expectOne(r => r.url.includes('/documents/debug/analyze-file'));
      expect(req.request.method).toBe('POST');
      req.flush(makeAnalysisResult({ fileName: 'retry.pdf' }));
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. isTableChunk() / getTableContent()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Chunk type helpers', () => {
    it('isTableChunk should return true when contentType is table', () => {
      const chunk = makeChunk({ contentType: 'table' });
      expect(component.isTableChunk(chunk)).toBeTrue();
    });

    it('isTableChunk should return false for text content type', () => {
      const chunk = makeChunk({ contentType: 'text' });
      expect(component.isTableChunk(chunk)).toBeFalse();
    });

    it('isTableChunk should return false for undefined contentType', () => {
      const chunk = makeChunk();
      expect(component.isTableChunk(chunk)).toBeFalse();
    });

    it('getTableContent should return fullTableContent when available', () => {
      const chunk = makeChunk({ fullTableContent: '| Col1 | Col2 |', content: 'Fallback' });
      expect(component.getTableContent(chunk)).toBe('| Col1 | Col2 |');
    });

    it('getTableContent should fall back to content when fullTableContent is absent', () => {
      const chunk = makeChunk({ content: 'Fallback content' });
      expect(component.getTableContent(chunk)).toBe('Fallback content');
    });

    it('getTableContent should return empty string when both are absent', () => {
      const chunk = makeChunk({ content: undefined as any, fullTableContent: undefined });
      expect(component.getTableContent(chunk)).toBe('');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. toggleChunkContent()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('toggleChunkContent()', () => {
    it('should expand a collapsed chunk', () => {
      component.expandedChunkIndex = null;
      component.toggleChunkContent(2);
      expect(component.expandedChunkIndex as any).toBe(2);
    });

    it('should collapse an already-expanded chunk', () => {
      component.expandedChunkIndex = 2;
      component.toggleChunkContent(2);
      expect(component.expandedChunkIndex).toBeNull();
    });

    it('should switch expanded chunk when a different one is toggled', () => {
      component.expandedChunkIndex = 1;
      component.toggleChunkContent(3);
      expect(component.expandedChunkIndex).toBe(3);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. getContentPreview()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getContentPreview()', () => {
    it('should return full content when it is within maxLength', () => {
      const result = component.getContentPreview('Short text', 100);
      expect(result).toBe('Short text');
    });

    it('should truncate and append ellipsis when content exceeds maxLength', () => {
      const longText = 'A'.repeat(150);
      const result = component.getContentPreview(longText, 100);
      expect(result.length).toBe(103); // 100 chars + '...'
      expect(result.endsWith('...')).toBeTrue();
    });

    it('should use default maxLength of 100', () => {
      const longText = 'B'.repeat(120);
      const result = component.getContentPreview(longText);
      expect(result.length).toBe(103);
    });

    it('should return empty string for null content', () => {
      expect(component.getContentPreview(null)).toBe('');
    });

    it('should return empty string for undefined content', () => {
      expect(component.getContentPreview(undefined)).toBe('');
    });

    it('should return empty string for empty string content', () => {
      expect(component.getContentPreview('')).toBe('');
    });

    it('should respect custom maxLength parameter', () => {
      const text = 'Hello World!';
      const result = component.getContentPreview(text, 5);
      expect(result).toBe('Hello...');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. copyChunkContent() / copyFirstLoadedDocumentContent()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Clipboard operations', () => {
    let clipboardSpy: jasmine.SpyObj<Clipboard>;
    let originalClipboard: Clipboard;

    beforeEach(() => {
      fixture.detectChanges();
      originalClipboard = navigator.clipboard;
      clipboardSpy = jasmine.createSpyObj('Clipboard', ['writeText']);
      clipboardSpy.writeText.and.returnValue(Promise.resolve());
      Object.defineProperty(navigator, 'clipboard', {
        value: clipboardSpy,
        configurable: true
      });
    });

    afterEach(() => {
      Object.defineProperty(navigator, 'clipboard', {
        value: originalClipboard,
        configurable: true
      });
    });

    it('copyChunkContent should write text to clipboard', fakeAsync(() => {
      component.copyChunkContent('Some chunk text to copy');
      tick();

      expect(clipboardSpy.writeText).toHaveBeenCalledWith('Some chunk text to copy');
    }));

    it('copyChunkContent should show success snackbar after copy', fakeAsync(() => {
      component.copyChunkContent('Copy me');
      tick();

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/copied to clipboard/i),
        'Close',
        jasmine.any(Object)
      );
    }));

    it('copyFirstLoadedDocumentContent should copy first document content', fakeAsync(() => {
      component.analysisResult = makeAnalysisResult();
      component.copyFirstLoadedDocumentContent();
      tick();

      expect(clipboardSpy.writeText).toHaveBeenCalledWith('Hello World document content');
    }));

    it('copyFirstLoadedDocumentContent should show error snackbar when no content', () => {
      component.analysisResult = null;
      component.copyFirstLoadedDocumentContent();

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/No document content available to copy/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });

    it('copyFirstLoadedDocumentContent should show error snackbar when loaded documents is empty', () => {
      component.analysisResult = makeAnalysisResult({ loadedDocuments: [] });
      component.copyFirstLoadedDocumentContent();

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/No document content available to copy/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. downloadFirstLoadedDocumentContent()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('downloadFirstLoadedDocumentContent()', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should show error snackbar when no content is available', () => {
      component.analysisResult = null;
      component.downloadFirstLoadedDocumentContent();

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/No document content available to download/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });

    it('should show error snackbar when loadedDocuments is empty', () => {
      component.analysisResult = makeAnalysisResult({ loadedDocuments: [] });
      component.downloadFirstLoadedDocumentContent();

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/No document content available to download/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });

    it('should trigger blob download when content is available', () => {
      component.analysisResult = makeAnalysisResult();

      // Spy on URL.createObjectURL and document.createElement to avoid real DOM side-effects
      spyOn(URL, 'createObjectURL').and.returnValue('blob:mock');
      spyOn(URL, 'revokeObjectURL');
      const linkEl = jasmine.createSpyObj('link', ['click']);
      linkEl.href = '';
      linkEl.download = '';
      linkEl.style = {};
      spyOn(document, 'createElement').and.returnValue(linkEl);
      spyOn(document.body, 'appendChild');
      spyOn(document.body, 'removeChild');

      component.downloadFirstLoadedDocumentContent();

      expect(URL.createObjectURL).toHaveBeenCalled();
      expect(linkEl.click).toHaveBeenCalled();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Content downloaded successfully/),
        'Close',
        jasmine.any(Object)
      );
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. OCR helpers: fileRequiresOcr() / fileMayBenefitFromOcr()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('File OCR classification helpers', () => {
    it('fileRequiresOcr should return true for image extensions', () => {
      const imageFiles = ['photo.png', 'scan.jpg', 'page.jpeg', 'image.tiff', 'picture.webp'];
      for (const name of imageFiles) {
        component.selectedFile = makeFile(name, 'image/*');
        expect(component.fileRequiresOcr()).toBeTrue();
      }
    });

    it('fileRequiresOcr should return false for PDF', () => {
      component.selectedFile = makeFile('document.pdf', 'application/pdf');
      expect(component.fileRequiresOcr()).toBeFalse();
    });

    it('fileRequiresOcr should return false when no file selected', () => {
      component.selectedFile = null;
      expect(component.fileRequiresOcr()).toBeFalse();
    });

    it('fileMayBenefitFromOcr should return true for PDF', () => {
      component.selectedFile = makeFile('report.pdf', 'application/pdf');
      expect(component.fileMayBenefitFromOcr()).toBeTrue();
    });

    it('fileMayBenefitFromOcr should return false for image files', () => {
      component.selectedFile = makeFile('scan.png', 'image/png');
      expect(component.fileMayBenefitFromOcr()).toBeFalse();
    });

    it('fileMayBenefitFromOcr should return false when no file selected', () => {
      component.selectedFile = null;
      expect(component.fileMayBenefitFromOcr()).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. isOcrAvailable()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('isOcrAvailable()', () => {
    it('should return true when ocrEnabled and pipelineReady', () => {
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: true });
      expect(component.isOcrAvailable()).toBeTrue();
    });

    it('should return false when ocrEnabled is false', () => {
      component.ocrStatus = makeOcrStatus({ ocrEnabled: false, pipelineReady: true });
      expect(component.isOcrAvailable()).toBeFalse();
    });

    it('should return false when pipelineReady is false', () => {
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: false });
      expect(component.isOcrAvailable()).toBeFalse();
    });

    it('should return false when ocrStatus is null', () => {
      component.ocrStatus = null;
      expect(component.isOcrAvailable()).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 13. shouldShowOcrWarning()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('shouldShowOcrWarning()', () => {
    it('should return false when OCR is available', () => {
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: true });
      component.selectedFile = makeFile('photo.png', 'image/png');
      expect(component.shouldShowOcrWarning()).toBeFalse();
    });

    it('should return true for image file when OCR unavailable', () => {
      component.ocrStatus = makeOcrStatus({ ocrEnabled: false, pipelineReady: false });
      component.selectedFile = makeFile('photo.png', 'image/png');
      expect(component.shouldShowOcrWarning()).toBeTrue();
    });

    it('should return true for PDF file when OCR unavailable', () => {
      component.ocrStatus = makeOcrStatus({ ocrEnabled: false, pipelineReady: false });
      component.selectedFile = makeFile('report.pdf', 'application/pdf');
      expect(component.shouldShowOcrWarning()).toBeTrue();
    });

    it('should check selectedFileName when no selectedFile', () => {
      component.ocrStatus = makeOcrStatus({ ocrEnabled: false, pipelineReady: false });
      component.selectedFile = null;
      component.selectedFileName = 'scan.jpg';
      expect(component.shouldShowOcrWarning()).toBeTrue();
    });

    it('should return false when no file selected and no file name', () => {
      component.ocrStatus = makeOcrStatus({ ocrEnabled: false, pipelineReady: false });
      component.selectedFile = null;
      component.selectedFileName = '';
      expect(component.shouldShowOcrWarning()).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 14. getOcrStatusText()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getOcrStatusText()', () => {
    it('should return loading message when isLoadingOcrStatus is true', () => {
      component.isLoadingOcrStatus = true;
      expect(component.getOcrStatusText()).toBe('Checking OCR status...');
    });

    it('should return error message when ocrStatusError is set', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = 'Connection refused';
      expect(component.getOcrStatusText()).toContain('OCR Error');
      expect(component.getOcrStatusText()).toContain('Connection refused');
    });

    it('should return unknown when ocrStatus is null', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = null;
      expect(component.getOcrStatusText()).toBe('OCR status unknown');
    });

    it('should return disabled message when ocrEnabled is false', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: false, pipelineReady: false });
      expect(component.getOcrStatusText()).toBe('OCR is disabled');
    });

    it('should return not-ready message when pipelineReady is false', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: false });
      expect(component.getOcrStatusText()).toContain('pipeline not ready');
    });

    it('should return available message when fully ready', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: true });
      expect(component.getOcrStatusText()).toBe('OCR is available');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 15. getOcrStatusColorClass()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getOcrStatusColorClass()', () => {
    it('should return ocr-loading when loading', () => {
      component.isLoadingOcrStatus = true;
      expect(component.getOcrStatusColorClass()).toBe('ocr-loading');
    });

    it('should return ocr-error when there is an error', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = 'Error';
      expect(component.getOcrStatusColorClass()).toBe('ocr-error');
    });

    it('should return ocr-error when OCR is disabled', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: false, pipelineReady: false });
      expect(component.getOcrStatusColorClass()).toBe('ocr-error');
    });

    it('should return ocr-warning when pipeline not ready', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: false });
      expect(component.getOcrStatusColorClass()).toBe('ocr-warning');
    });

    it('should return ocr-success when fully available', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: true });
      expect(component.getOcrStatusColorClass()).toBe('ocr-success');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 16. getOcrStatusIcon()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getOcrStatusIcon()', () => {
    it('should return hourglass_empty when loading', () => {
      component.isLoadingOcrStatus = true;
      expect(component.getOcrStatusIcon()).toBe('hourglass_empty');
    });

    it('should return error icon when ocrStatusError is set', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = 'Some error';
      expect(component.getOcrStatusIcon()).toBe('error');
    });

    it('should return visibility_off when OCR is disabled', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: false, pipelineReady: false });
      expect(component.getOcrStatusIcon()).toBe('visibility_off');
    });

    it('should return warning when pipeline not ready', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: false });
      expect(component.getOcrStatusIcon()).toBe('warning');
    });

    it('should return check_circle when fully available', () => {
      component.isLoadingOcrStatus = false;
      component.ocrStatusError = null;
      component.ocrStatus = makeOcrStatus({ ocrEnabled: true, pipelineReady: true });
      expect(component.getOcrStatusIcon()).toBe('check_circle');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 17. getLoadedOcrModelCount()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getLoadedOcrModelCount()', () => {
    it('should return count of loaded models', () => {
      component.ocrModels = [
        { modelId: 'm1', type: 'vlm', isLoaded: true },
        { modelId: 'm2', type: 'ocr', isLoaded: false },
        { modelId: 'm3', type: 'layout', isLoaded: true }
      ];
      expect(component.getLoadedOcrModelCount()).toBe(2);
    });

    it('should return 0 when no models loaded', () => {
      component.ocrModels = [
        { modelId: 'm1', type: 'vlm', isLoaded: false }
      ];
      expect(component.getLoadedOcrModelCount()).toBe(0);
    });

    it('should return 0 for empty models array', () => {
      component.ocrModels = [];
      expect(component.getLoadedOcrModelCount()).toBe(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 18. loadOcrStatus() / refreshOcrStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadOcrStatus() / refreshOcrStatus()', () => {
    it('should call OcrService.getStatus, getConfig, getModels', () => {
      fixture.detectChanges(); // triggers loadOcrStatus via ngOnInit
      expect(spies.ocrServiceSpy.getStatus).toHaveBeenCalled();
      expect(spies.ocrServiceSpy.getConfig).toHaveBeenCalled();
      expect(spies.ocrServiceSpy.getModels).toHaveBeenCalled();
    });

    it('should set ocrStatus on successful getStatus response', () => {
      const status = makeOcrStatus({ ocrEnabled: true, pipelineReady: true });
      spies.ocrServiceSpy.getStatus.and.returnValue(of(status));

      fixture.detectChanges();

      expect(component.ocrStatus).toEqual(status);
      expect(component.isLoadingOcrStatus).toBeFalse();
    });

    it('should set ocrStatusError and fallback status on getStatus error', () => {
      spies.ocrServiceSpy.getStatus.and.returnValue(
        throwError(() => ({ message: 'Connection refused' }))
      );

      fixture.detectChanges();

      expect(component.ocrStatusError).toBeTruthy();
      expect(component.ocrStatus).toBeDefined();
      expect(component.ocrStatus?.ocrEnabled).toBeFalse();
      expect(component.isLoadingOcrStatus).toBeFalse();
    });

    it('should set ocrConfig on successful getConfig response', () => {
      const config: OcrConfig = { ocrEnabled: true, useVlm: true };
      spies.ocrServiceSpy.getConfig.and.returnValue(of(config));

      fixture.detectChanges();

      expect(component.ocrConfig).toEqual(config);
    });

    it('should set ocrModels on successful getModels response', () => {
      const models: OcrModelInfo[] = [
        { modelId: 'model-1', type: 'vlm', isLoaded: true }
      ];
      spies.ocrServiceSpy.getModels.and.returnValue(of(models));

      fixture.detectChanges();

      expect(component.ocrModels.length).toBe(1);
      expect(component.ocrModels[0].modelId).toBe('model-1');
    });

    it('refreshOcrStatus should call loadOcrStatus again', () => {
      fixture.detectChanges();
      const callCountBefore = spies.ocrServiceSpy.getStatus.calls.count();

      component.refreshOcrStatus();

      expect(spies.ocrServiceSpy.getStatus.calls.count()).toBeGreaterThan(callCountBefore);
    });

    it('should gracefully handle getConfig error without affecting status', () => {
      spies.ocrServiceSpy.getConfig.and.returnValue(
        throwError(() => new Error('Config unavailable'))
      );

      // Should not throw
      expect(() => fixture.detectChanges()).not.toThrow();
      expect(component.ocrStatus).toBeTruthy(); // status still loaded
    });

    it('should gracefully handle getModels error without affecting status', () => {
      spies.ocrServiceSpy.getModels.and.returnValue(
        throwError(() => new Error('Models unavailable'))
      );

      expect(() => fixture.detectChanges()).not.toThrow();
      expect(component.ocrStatus).toBeTruthy();
    });
  });
});
