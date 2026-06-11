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

import { DocumentService } from './document.service';
import {
  AddUrlRequest,
  AddPathRequest,
  AddTextRequest,
  AddTextResponse,
  AsyncUploadResponse,
  BatchProcessRequest,
  BatchProcessResponse,
  CancelTaskResponse,
  ChunkerInfo,
  DebugAnalysisResult,
  DebuggerStatus,
  DocumentSourceType,
  FileUploadResponse,
  IngestPhase,
  IngestProgressUpdate,
  IngestStatus,
  LoaderInfo,
  ProcessingModeInfo,
  SimpleMessageResponse,
  SubprocessIngestConfig,
  TestUploadResponse,
  UploadedFileInfo
} from '../models/api-models';

describe('DocumentService', () => {
  let service: DocumentService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [DocumentService]
    });

    service = TestBed.inject(DocumentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. getConfiguredSources()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getConfiguredSources()', () => {
    it('should GET /api/documents/sources and return string array', () => {
      const mockSources = ['file', 'url', 'text'];

      service.getConfiguredSources().subscribe(sources => {
        expect(sources).toEqual(['file', 'url', 'text']);
        expect(sources.length).toBe(3);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/sources'));
      expect(req.request.method).toBe('GET');
      req.flush(mockSources);
    });

    it('should handle server error on getConfiguredSources', () => {
      service.getConfiguredSources().subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/sources'));
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. getUploadedFiles()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getUploadedFiles()', () => {
    it('should GET /api/documents/uploaded-files and return file list', () => {
      const mockFiles: UploadedFileInfo[] = [
        { fileName: 'report.pdf', filePath: '/uploads/report.pdf' },
        { fileName: 'notes.txt', filePath: '/uploads/notes.txt' }
      ];
      const mockResponse = { uploaded_files_location: '/uploads', files: mockFiles };

      service.getUploadedFiles().subscribe(response => {
        expect(response.uploaded_files_location).toBe('/uploads');
        expect(response.files.length).toBe(2);
        expect(response.files[0].fileName).toBe('report.pdf');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/uploaded-files'));
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });

    it('should return empty file list when no files uploaded', () => {
      const mockResponse = { uploaded_files_location: '/uploads', files: [] };

      service.getUploadedFiles().subscribe(response => {
        expect(response.files.length).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/uploaded-files'));
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. getAvailableLoaders()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getAvailableLoaders()', () => {
    it('should GET /api/documents/loaders and return loader list', () => {
      const mockLoaders: LoaderInfo[] = [
        { name: 'pdf', className: 'PdfDocumentLoader' },
        { name: 'tika', className: 'TikaDocumentLoader' }
      ];

      service.getAvailableLoaders().subscribe(loaders => {
        expect(loaders.length).toBe(2);
        expect(loaders[0].name).toBe('pdf');
        expect(loaders[1].className).toBe('TikaDocumentLoader');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/loaders'));
      expect(req.request.method).toBe('GET');
      req.flush(mockLoaders);
    });

    it('should handle error when loaders endpoint is unavailable', () => {
      service.getAvailableLoaders().subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/loaders'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. getAvailableChunkers()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getAvailableChunkers()', () => {
    it('should GET /api/documents/chunkers and return chunker list', () => {
      const mockChunkers: ChunkerInfo[] = [
        { name: 'recursive-character', className: 'RecursiveCharacterChunker' },
        { name: 'sentence', className: 'SentenceChunker' }
      ];

      service.getAvailableChunkers().subscribe(chunkers => {
        expect(chunkers.length).toBe(2);
        expect(chunkers[0].name).toBe('recursive-character');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/chunkers'));
      expect(req.request.method).toBe('GET');
      req.flush(mockChunkers);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. uploadFile()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('uploadFile()', () => {
    it('should POST multipart form to /api/documents/upload with file', () => {
      const mockFile = new File(['file content'], 'test.pdf', { type: 'application/pdf' });
      const mockResponse: FileUploadResponse = {
        message: 'File uploaded successfully',
        fileName: 'test.pdf',
        filePath: '/uploads/test.pdf'
      };

      service.uploadFile(mockFile).subscribe(response => {
        expect(response.message).toBe('File uploaded successfully');
        expect(response.fileName).toBe('test.pdf');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/upload'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeInstanceOf(FormData);
      req.flush(mockResponse);
    });

    it('should include loader name in FormData when provided', () => {
      const mockFile = new File(['content'], 'doc.pdf', { type: 'application/pdf' });
      const mockResponse: FileUploadResponse = { message: 'Uploaded', fileName: 'doc.pdf' };

      service.uploadFile(mockFile, 'pdf').subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/upload'));
      expect(req.request.method).toBe('POST');
      const formData: FormData = req.request.body;
      expect(formData.get('loader')).toBe('pdf');
      req.flush(mockResponse);
    });

    it('should not include loader field when loaderName is omitted', () => {
      const mockFile = new File(['content'], 'doc.txt', { type: 'text/plain' });

      service.uploadFile(mockFile).subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/upload'));
      const formData: FormData = req.request.body;
      expect(formData.get('loader')).toBeNull();
      req.flush({ message: 'Uploaded', fileName: 'doc.txt' });
    });

    it('should handle upload failure (413 Payload Too Large)', () => {
      const mockFile = new File(['large content'], 'huge.pdf', { type: 'application/pdf' });

      service.uploadFile(mockFile).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/upload'));
      req.flush('Payload Too Large', { status: 413, statusText: 'Payload Too Large' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. uploadFileAsync()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('uploadFileAsync()', () => {
    it('should POST to /api/documents/upload-async and return task info', () => {
      const mockFile = new File(['content'], 'report.pdf', { type: 'application/pdf' });
      const mockResponse: AsyncUploadResponse = {
        taskId: 'task-abc-123',
        fileName: 'report.pdf',
        message: 'File accepted for async processing',
        websocketTopic: '/topic/ingest/task-abc-123',
        accepted: true,
        error: null
      };

      service.uploadFileAsync(mockFile).subscribe(response => {
        expect(response.accepted).toBeTrue();
        expect(response.taskId).toBe('task-abc-123');
        expect(response.websocketTopic).toBe('/topic/ingest/task-abc-123');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/upload-async'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeInstanceOf(FormData);
      req.flush(mockResponse);
    });

    it('should include loader, chunkerName, and processingMode in FormData', () => {
      const mockFile = new File(['content'], 'doc.pdf', { type: 'application/pdf' });

      service.uploadFileAsync(mockFile, 'pdf', 'sentence', 'subprocess').subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/upload-async'));
      const formData: FormData = req.request.body;
      expect(formData.get('loader')).toBe('pdf');
      expect(formData.get('chunkerName')).toBe('sentence');
      expect(formData.get('processingMode')).toBe('subprocess');
      req.flush({ taskId: 'task-1', fileName: 'doc.pdf', message: 'OK', websocketTopic: null, accepted: true, error: null });
    });

    it('should default processingMode to auto when not specified', () => {
      const mockFile = new File(['content'], 'doc.pdf', { type: 'application/pdf' });

      service.uploadFileAsync(mockFile).subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/upload-async'));
      const formData: FormData = req.request.body;
      expect(formData.get('processingMode')).toBe('auto');
      req.flush({ taskId: 'task-2', fileName: 'doc.pdf', message: 'OK', websocketTopic: null, accepted: true, error: null });
    });

    it('should include subprocess config fields when subprocessConfig is provided', () => {
      const mockFile = new File(['content'], 'doc.pdf', { type: 'application/pdf' });
      const subConfig: SubprocessIngestConfig = {
        enabled: true,
        heapSize: '8g',
        offHeapMaxBytes: '16g',
        timeoutMinutes: 120,
        heartbeatIntervalSeconds: 15,
        staleThresholdSeconds: 180
      };

      service.uploadFileAsync(mockFile, undefined, undefined, 'subprocess', subConfig).subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/upload-async'));
      const formData: FormData = req.request.body;
      expect(formData.get('subprocessHeapSize')).toBe('8g');
      expect(formData.get('subprocessTimeoutMinutes')).toBe('120');
      expect(formData.get('subprocessHeartbeatSeconds')).toBe('15');
      expect(formData.get('subprocessStaleThresholdSeconds')).toBe('180');
      req.flush({ taskId: 'task-3', fileName: 'doc.pdf', message: 'OK', websocketTopic: null, accepted: true, error: null });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. addUrl()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('addUrl()', () => {
    it('should POST to /api/documents/add-url with URL request body', () => {
      const request: AddUrlRequest = {
        url: 'https://example.com/doc.html',
        fileName: 'doc.html',
        loaderName: 'tika'
      };
      const mockResponse: FileUploadResponse = {
        message: 'URL added successfully',
        fileName: 'doc.html'
      };

      service.addUrl(request).subscribe(response => {
        expect(response.message).toBe('URL added successfully');
        expect(response.fileName).toBe('doc.html');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/add-url'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.url).toBe('https://example.com/doc.html');
      expect(req.request.body.loaderName).toBe('tika');
      req.flush(mockResponse);
    });

    it('should handle invalid URL error (400)', () => {
      const request: AddUrlRequest = { url: 'not-a-url' };

      service.addUrl(request).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/add-url'));
      req.flush({ error: 'Invalid URL' }, { status: 400, statusText: 'Bad Request' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. addPath()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('addPath()', () => {
    it('should POST to /api/documents/add-path with path request body', () => {
      const request: AddPathRequest = {
        path: '/data/documents',
        chunkerName: 'recursive-character'
      };
      const mockResponse: SimpleMessageResponse = {
        message: 'Path added successfully'
      };

      service.addPath(request).subscribe(response => {
        expect(response.message).toBe('Path added successfully');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/add-path'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.path).toBe('/data/documents');
      expect(req.request.body.chunkerName).toBe('recursive-character');
      req.flush(mockResponse);
    });

    it('should handle path not found error (404)', () => {
      const request: AddPathRequest = { path: '/nonexistent/path' };

      service.addPath(request).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/add-path'));
      req.flush({ message: 'Path not found' }, { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. addTextContent()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('addTextContent()', () => {
    it('should POST to /api/documents/add-text with text content', () => {
      const request: AddTextRequest = {
        content: 'This is some text content to be indexed.',
        sourceName: 'clipboard-snippet',
        chunkerName: 'sentence'
      };
      const mockResponse: AddTextResponse = {
        message: 'Text content added successfully',
        sourceName: 'clipboard-snippet',
        contentLength: 41,
        wordCount: 8
      };

      service.addTextContent(request).subscribe(response => {
        expect(response.message).toBe('Text content added successfully');
        expect(response.sourceName).toBe('clipboard-snippet');
        expect(response.wordCount).toBe(8);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/add-text'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.content).toBe('This is some text content to be indexed.');
      expect(req.request.body.sourceName).toBe('clipboard-snippet');
      req.flush(mockResponse);
    });

    it('should handle empty content error', () => {
      const request: AddTextRequest = { content: '' };

      service.addTextContent(request).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/add-text'));
      req.flush({ error: 'Content cannot be empty' }, { status: 400, statusText: 'Bad Request' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. processBatch()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('processBatch()', () => {
    it('should POST to /api/documents/process-batch with batch request', () => {
      const request: BatchProcessRequest = {
        items: [
          { pathOrUrl: 'https://example.com/a.html', type: DocumentSourceType.URL },
          { pathOrUrl: '/local/b.pdf', type: DocumentSourceType.FILE }
        ],
        defaultLoaderName: 'tika'
      };
      const mockResponse: BatchProcessResponse = {
        message: 'Batch processed',
        successful_items: 2,
        failed_items: 0,
        details: null
      };

      service.processBatch(request).subscribe(response => {
        expect(response.successful_items).toBe(2);
        expect(response.failed_items).toBe(0);
        expect(response.message).toBe('Batch processed');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/process-batch'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.items.length).toBe(2);
      expect(req.request.body.defaultLoaderName).toBe('tika');
      req.flush(mockResponse);
    });

    it('should report partial failures in batch response', () => {
      const request: BatchProcessRequest = {
        items: [
          { pathOrUrl: '/good.pdf', type: DocumentSourceType.FILE },
          { pathOrUrl: '/bad.pdf', type: DocumentSourceType.FILE }
        ]
      };
      const mockResponse: BatchProcessResponse = {
        message: 'Partial success',
        successful_items: 1,
        failed_items: 1,
        details: null
      };

      service.processBatch(request).subscribe(response => {
        expect(response.successful_items).toBe(1);
        expect(response.failed_items).toBe(1);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/process-batch'));
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. getIngestStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getIngestStatus()', () => {
    it('should GET /api/documents/ingest-status/:taskId and return progress update', () => {
      const taskId = 'task-xyz-789';
      const mockUpdate: IngestProgressUpdate = {
        taskId: taskId,
        fileName: 'large-doc.pdf',
        phase: IngestPhase.EMBEDDING,
        status: IngestStatus.IN_PROGRESS,
        progressPercent: 65,
        currentStep: 'Embedding chunk 650 of 1000',
        message: 'Embedding in progress',
        stats: null,
        errorMessage: null,
        timestamp: new Date().toISOString(),
        factSheetId: null
      };

      service.getIngestStatus(taskId).subscribe(update => {
        expect(update.taskId).toBe(taskId);
        expect(update.phase).toBe(IngestPhase.EMBEDDING);
        expect(update.progressPercent).toBe(65);
        expect(update.status).toBe(IngestStatus.IN_PROGRESS);
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/documents/ingest-status/${taskId}`));
      expect(req.request.method).toBe('GET');
      req.flush(mockUpdate);
    });

    it('should return COMPLETED status when task finishes', () => {
      const taskId = 'task-done-001';
      const mockUpdate: IngestProgressUpdate = {
        taskId,
        fileName: 'finished.pdf',
        phase: IngestPhase.COMPLETED,
        status: IngestStatus.COMPLETED,
        progressPercent: 100,
        currentStep: 'Done',
        message: 'Ingest completed successfully',
        stats: null,
        errorMessage: null,
        timestamp: new Date().toISOString(),
        factSheetId: null
      };

      service.getIngestStatus(taskId).subscribe(update => {
        expect(update.status).toBe(IngestStatus.COMPLETED);
        expect(update.progressPercent).toBe(100);
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/documents/ingest-status/${taskId}`));
      req.flush(mockUpdate);
    });

    it('should handle 404 when task not found', () => {
      service.getIngestStatus('nonexistent-task').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.includes('/documents/ingest-status/'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. getAllIngestTasks()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getAllIngestTasks()', () => {
    it('should GET /api/documents/ingest-tasks and return all active tasks', () => {
      const mockTasks: IngestProgressUpdate[] = [
        {
          taskId: 'task-1',
          fileName: 'a.pdf',
          phase: IngestPhase.LOADING,
          status: IngestStatus.IN_PROGRESS,
          progressPercent: 10,
          currentStep: 'Loading',
          message: 'Loading document',
          stats: null,
          errorMessage: null,
          timestamp: new Date().toISOString(),
          factSheetId: null
        },
        {
          taskId: 'task-2',
          fileName: 'b.pdf',
          phase: IngestPhase.CHUNKING,
          status: IngestStatus.IN_PROGRESS,
          progressPercent: 50,
          currentStep: 'Chunking',
          message: 'Chunking document',
          stats: null,
          errorMessage: null,
          timestamp: new Date().toISOString(),
          factSheetId: null
        }
      ];

      service.getAllIngestTasks().subscribe(tasks => {
        expect(tasks.length).toBe(2);
        expect(tasks[0].taskId).toBe('task-1');
        expect(tasks[1].phase).toBe(IngestPhase.CHUNKING);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/ingest-tasks'));
      expect(req.request.method).toBe('GET');
      req.flush(mockTasks);
    });

    it('should return empty array when no tasks are active', () => {
      service.getAllIngestTasks().subscribe(tasks => {
        expect(tasks.length).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/ingest-tasks'));
      req.flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 13. cancelIngestTask()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('cancelIngestTask()', () => {
    it('should POST to /api/documents/ingest-cancel/:taskId and return cancel response', () => {
      const taskId = 'task-to-cancel-456';
      const mockResponse: CancelTaskResponse = {
        taskId,
        cancelled: true,
        message: 'Task cancellation requested'
      };

      service.cancelIngestTask(taskId).subscribe(response => {
        expect(response.cancelled).toBeTrue();
        expect(response.taskId).toBe(taskId);
        expect(response.message).toBe('Task cancellation requested');
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/documents/ingest-cancel/${taskId}`));
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);
    });

    it('should handle error when cancelling unknown task', () => {
      service.cancelIngestTask('nonexistent').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.includes('/documents/ingest-cancel/'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 14. getDebuggerStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getDebuggerStatus()', () => {
    it('should GET /api/documents/debug/status and return debugger status', () => {
      const mockStatus: DebuggerStatus = {
        uploadsPathConfigured: true,
        uploadsPath: '/var/data/uploads',
        totalLoaders: 5,
        realLoaders: 4,
        noOpLoaders: 1,
        totalChunkers: 8,
        realChunkers: 7,
        noOpChunkers: 1
      };

      service.getDebuggerStatus().subscribe(status => {
        expect(status.uploadsPathConfigured).toBeTrue();
        expect(status.totalLoaders).toBe(5);
        expect(status.realLoaders).toBe(4);
        expect(status.totalChunkers).toBe(8);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/debug/status'));
      expect(req.request.method).toBe('GET');
      req.flush(mockStatus);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 15. analyzeFile()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('analyzeFile()', () => {
    it('should POST to /api/documents/debug/analyze-file with fileName query param', () => {
      const mockResult: DebugAnalysisResult = {
        fileName: 'test.pdf',
        filePath: '/uploads/test.pdf',
        fileSize: 10240,
        availableLoaders: null,
        selectedLoader: null,
        loadedDocuments: null,
        availableChunkers: null,
        selectedChunker: null,
        chunks: null,
        processingStats: null,
        errorMessage: null
      };

      service.analyzeFile('test.pdf').subscribe(result => {
        expect(result.fileName).toBe('test.pdf');
        expect(result.fileSize).toBe(10240);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/debug/analyze-file') && r.params.get('fileName') === 'test.pdf');
      expect(req.request.method).toBe('POST');
      req.flush(mockResult);
    });

    it('should include loaderName and chunkerName params when provided', () => {
      service.analyzeFile('doc.pdf', 'pdf', 'sentence').subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/documents/debug/analyze-file') &&
        r.params.get('fileName') === 'doc.pdf'
      );
      expect(req.request.params.get('loaderName')).toBe('pdf');
      expect(req.request.params.get('chunkerName')).toBe('sentence');
      req.flush({
        fileName: 'doc.pdf',
        filePath: null,
        fileSize: 0,
        availableLoaders: null,
        selectedLoader: null,
        loadedDocuments: null,
        availableChunkers: null,
        selectedChunker: null,
        chunks: null,
        processingStats: null,
        errorMessage: null
      });
    });

    it('should not include optional params when omitted', () => {
      service.analyzeFile('plain.txt').subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/debug/analyze-file'));
      expect(req.request.params.get('loaderName')).toBeNull();
      expect(req.request.params.get('chunkerName')).toBeNull();
      req.flush({
        fileName: 'plain.txt',
        filePath: null,
        fileSize: 0,
        availableLoaders: null,
        selectedLoader: null,
        loadedDocuments: null,
        availableChunkers: null,
        selectedChunker: null,
        chunks: null,
        processingStats: null,
        errorMessage: null
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 16. testUploadDebugFile()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('testUploadDebugFile()', () => {
    it('should POST multipart form to /api/documents/debug/test-upload', () => {
      const mockFile = new File(['debug content'], 'debug-test.pdf', { type: 'application/pdf' });
      const mockResponse: TestUploadResponse = {
        message: 'Test upload successful',
        fileName: 'debug-test.pdf',
        filePath: '/uploads/debug-test.pdf',
        fileSize: 13
      };

      service.testUploadDebugFile(mockFile).subscribe(response => {
        expect(response.message).toBe('Test upload successful');
        expect(response.fileName).toBe('debug-test.pdf');
        expect(response.fileSize).toBe(13);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/debug/test-upload'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeInstanceOf(FormData);
      req.flush(mockResponse);
    });

    it('should handle upload error in test-upload endpoint', () => {
      const mockFile = new File(['content'], 'bad.pdf', { type: 'application/pdf' });

      service.testUploadDebugFile(mockFile).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/debug/test-upload'));
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 17. getProcessingModes()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getProcessingModes()', () => {
    it('should GET /api/documents/processing-modes and return modes list', () => {
      const mockModes: ProcessingModeInfo[] = [
        { value: 'auto', label: 'Auto', description: 'Automatically choose the best mode' },
        { value: 'subprocess', label: 'Subprocess', description: 'Run in separate JVM process' },
        { value: 'inprocess', label: 'In-Process', description: 'Run in the same JVM process' }
      ];
      const mockResponse = { modes: mockModes, default: 'auto' };

      service.getProcessingModes().subscribe(response => {
        expect(response.modes.length).toBe(3);
        expect(response.default).toBe('auto');
        expect(response.modes[0].value).toBe('auto');
        expect(response.modes[1].value).toBe('subprocess');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/processing-modes'));
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });

    it('should handle error when processing modes endpoint is unavailable', () => {
      service.getProcessingModes().subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/documents/processing-modes'));
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });
  });
});
