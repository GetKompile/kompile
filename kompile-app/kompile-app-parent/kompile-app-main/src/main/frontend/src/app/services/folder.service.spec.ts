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

import { FolderService } from './folder.service';
import {
  ChatFolder,
  ChatSessionDto,
  CreateFolderRequest,
  FolderContext,
  FolderFile,
  UpdateFolderRequest
} from '../models/api-models';

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeFolder(overrides: Partial<ChatFolder> = {}): ChatFolder {
  return {
    folderId: 'folder-001',
    name: 'My Folder',
    description: 'A test folder',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    fileCount: 0,
    ...overrides
  };
}

function makeFile(overrides: Partial<FolderFile> = {}): FolderFile {
  return {
    fileId: 'file-001',
    fileName: 'test.pdf',
    storedPath: '/uploads/test.pdf',
    fileSize: 2048,
    uploadedAt: new Date().toISOString(),
    ...overrides
  };
}

// ─────────────────────────────────────────────────────────────────────────────

describe('FolderService', () => {
  let service: FolderService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [FolderService]
    });

    service = TestBed.inject(FolderService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. createFolder()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('createFolder()', () => {
    it('should POST to /api/folders with create request and return folder', () => {
      const request: CreateFolderRequest = {
        name: 'Research Papers',
        description: 'Papers for Q1 research'
      };
      const mockFolder = makeFolder({ folderId: 'folder-new', name: 'Research Papers', description: 'Papers for Q1 research' });

      service.createFolder(request).subscribe(folder => {
        expect(folder.folderId).toBe('folder-new');
        expect(folder.name).toBe('Research Papers');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/folders'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.name).toBe('Research Papers');
      req.flush(mockFolder);
    });

    it('should update the folders$ BehaviorSubject after successful create', () => {
      const request: CreateFolderRequest = { name: 'New Folder' };
      const mockFolder = makeFolder({ folderId: 'folder-new', name: 'New Folder' });

      let emittedFolders: ChatFolder[] = [];
      service.folders$.subscribe(folders => (emittedFolders = folders));

      service.createFolder(request).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/folders'));
      req.flush(mockFolder);

      expect(emittedFolders.some(f => f.folderId === 'folder-new')).toBeTrue();
    });

    it('should handle 400 error when folder name is missing', () => {
      const request: CreateFolderRequest = { name: '' };

      service.createFolder(request).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/folders'));
      req.flush({ message: 'Name is required' }, { status: 400, statusText: 'Bad Request' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. getFolders()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getFolders()', () => {
    it('should GET /api/folders and return folder list', () => {
      const mockFolders = [
        makeFolder({ folderId: 'f-1', name: 'Folder A' }),
        makeFolder({ folderId: 'f-2', name: 'Folder B' })
      ];

      service.getFolders().subscribe(folders => {
        expect(folders.length).toBe(2);
        expect(folders[0].folderId).toBe('f-1');
        expect(folders[1].name).toBe('Folder B');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/folders'));
      expect(req.request.method).toBe('GET');
      req.flush(mockFolders);
    });

    it('should include userId as query parameter when provided', () => {
      const userId = 'user-123';
      const mockFolders = [makeFolder({ userId })];

      service.getFolders(userId).subscribe();

      const req = httpMock.expectOne(r => r.url.includes('/folders') && r.url.includes('userId=user-123'));
      expect(req.request.method).toBe('GET');
      req.flush(mockFolders);
    });

    it('should update folders$ BehaviorSubject after fetching', () => {
      const mockFolders = [makeFolder({ folderId: 'f-1' }), makeFolder({ folderId: 'f-2' })];

      let emittedFolders: ChatFolder[] = [];
      service.folders$.subscribe(folders => (emittedFolders = folders));

      service.getFolders().subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/folders'));
      req.flush(mockFolders);

      expect(emittedFolders.length).toBe(2);
    });

    it('should handle server error', () => {
      service.getFolders().subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/folders'));
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. getFolder()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getFolder()', () => {
    it('should GET /api/folders/:folderId and return the folder', () => {
      const folderId = 'folder-abc';
      const mockFolder = makeFolder({ folderId, name: 'Specific Folder', files: [] });

      service.getFolder(folderId).subscribe(folder => {
        expect(folder.folderId).toBe(folderId);
        expect(folder.name).toBe('Specific Folder');
        expect(folder.files).toEqual([]);
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}`));
      expect(req.request.method).toBe('GET');
      req.flush(mockFolder);
    });

    it('should handle 404 when folder not found', () => {
      service.getFolder('nonexistent').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/folders/nonexistent'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. updateFolder()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('updateFolder()', () => {
    it('should PUT /api/folders/:folderId with update request', () => {
      const folderId = 'folder-xyz';
      const request: UpdateFolderRequest = { name: 'Renamed Folder', description: 'New description' };
      const mockFolder = makeFolder({ folderId, name: 'Renamed Folder', description: 'New description' });

      service.updateFolder(folderId, request).subscribe(folder => {
        expect(folder.name).toBe('Renamed Folder');
        expect(folder.description).toBe('New description');
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}`));
      expect(req.request.method).toBe('PUT');
      expect(req.request.body.name).toBe('Renamed Folder');
      req.flush(mockFolder);
    });

    it('should update the folders$ cache after successful update', () => {
      // Pre-populate cache
      const folderId = 'folder-001';
      const existingFolders = [makeFolder({ folderId, name: 'Old Name' })];
      (service as any).foldersSubject.next(existingFolders);

      const request: UpdateFolderRequest = { name: 'New Name' };
      const updatedFolder = makeFolder({ folderId, name: 'New Name' });

      let emittedFolders: ChatFolder[] = [];
      service.folders$.subscribe(folders => (emittedFolders = folders));

      service.updateFolder(folderId, request).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}`));
      req.flush(updatedFolder);

      const found = emittedFolders.find(f => f.folderId === folderId);
      expect(found?.name).toBe('New Name');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. deleteFolder()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('deleteFolder()', () => {
    it('should DELETE /api/folders/:folderId', () => {
      const folderId = 'folder-del';

      service.deleteFolder(folderId).subscribe(result => {
        expect(result).toBeNull(); // void response flushed as null
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}`));
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });

    it('should remove folder from folders$ cache after deletion', () => {
      const folderId = 'folder-to-delete';
      const existingFolders = [
        makeFolder({ folderId, name: 'To Delete' }),
        makeFolder({ folderId: 'folder-keep', name: 'Keep Me' })
      ];
      (service as any).foldersSubject.next(existingFolders);

      let emittedFolders: ChatFolder[] = [];
      service.folders$.subscribe(folders => (emittedFolders = folders));

      service.deleteFolder(folderId).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}`));
      req.flush(null);

      expect(emittedFolders.some(f => f.folderId === folderId)).toBeFalse();
      expect(emittedFolders.some(f => f.folderId === 'folder-keep')).toBeTrue();
    });

    it('should clear selectedFolder$ when the selected folder is deleted', () => {
      const folderId = 'selected-folder';
      const folder = makeFolder({ folderId });
      (service as any).foldersSubject.next([folder]);
      (service as any).selectedFolderSubject.next(folder);

      let selected: ChatFolder | null = folder;
      service.selectedFolder$.subscribe(f => (selected = f));

      service.deleteFolder(folderId).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}`));
      req.flush(null);

      expect(selected).toBeNull();
    });

    it('should handle 404 when folder to delete does not exist', () => {
      service.deleteFolder('ghost-folder').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/folders/ghost-folder'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. uploadFile()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('uploadFile()', () => {
    it('should POST multipart form to /api/folders/:folderId/files', () => {
      const folderId = 'folder-001';
      const mockFile = new File(['content'], 'document.pdf', { type: 'application/pdf' });
      const mockFolderFile = makeFile({ fileId: 'file-new', fileName: 'document.pdf' });

      service.uploadFile(folderId, mockFile).subscribe(file => {
        expect(file.fileId).toBe('file-new');
        expect(file.fileName).toBe('document.pdf');
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}/files`));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeInstanceOf(FormData);
      req.flush(mockFolderFile);
    });

    it('should handle upload failure with 413 error', () => {
      const folderId = 'folder-001';
      const bigFile = new File(['x'.repeat(100)], 'huge.pdf', { type: 'application/pdf' });

      service.uploadFile(folderId, bigFile).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}/files`));
      req.flush('Payload Too Large', { status: 413, statusText: 'Payload Too Large' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. getFolderFiles()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getFolderFiles()', () => {
    it('should GET /api/folders/:folderId/files and return file list', () => {
      const folderId = 'folder-001';
      const mockFiles = [
        makeFile({ fileId: 'file-1', fileName: 'a.pdf' }),
        makeFile({ fileId: 'file-2', fileName: 'b.docx' })
      ];

      service.getFolderFiles(folderId).subscribe(files => {
        expect(files.length).toBe(2);
        expect(files[0].fileName).toBe('a.pdf');
        expect(files[1].fileId).toBe('file-2');
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}/files`));
      expect(req.request.method).toBe('GET');
      req.flush(mockFiles);
    });

    it('should return empty array when folder has no files', () => {
      service.getFolderFiles('empty-folder').subscribe(files => {
        expect(files.length).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/folders/empty-folder/files'));
      req.flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. deleteFile()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('deleteFile()', () => {
    it('should DELETE /api/folders/:folderId/files/:fileId', () => {
      const folderId = 'folder-001';
      const fileId = 'file-001';

      service.deleteFile(folderId, fileId).subscribe(result => {
        expect(result).toBeNull(); // void response flushed as null
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}/files/${fileId}`));
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });

    it('should handle 404 when file does not exist', () => {
      service.deleteFile('folder-001', 'nonexistent-file').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/folders/folder-001/files/nonexistent-file'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. associateSession()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('associateSession()', () => {
    it('should POST to /api/folders/:folderId/sessions/:sessionId', () => {
      const folderId = 'folder-001';
      const sessionId = 'session-abc';
      const mockSession: ChatSessionDto = {
        sessionId,
        title: 'My Session',
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        folderId
      };

      service.associateSession(folderId, sessionId).subscribe(session => {
        expect(session.sessionId).toBe(sessionId);
        expect(session.folderId).toBe(folderId);
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}/sessions/${sessionId}`));
      expect(req.request.method).toBe('POST');
      req.flush(mockSession);
    });

    it('should handle 404 when folder or session does not exist', () => {
      service.associateSession('bad-folder', 'bad-session').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/folders/bad-folder/sessions/bad-session'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. disassociateSession()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('disassociateSession()', () => {
    it('should DELETE /api/folders/:folderId/sessions/:sessionId', () => {
      const folderId = 'folder-001';
      const sessionId = 'session-abc';

      service.disassociateSession(folderId, sessionId).subscribe(result => {
        expect(result).toBeNull(); // void response flushed as null
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}/sessions/${sessionId}`));
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. getFolderContext()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getFolderContext()', () => {
    it('should GET /api/folders/:folderId/context and return folder context', () => {
      const folderId = 'folder-001';
      const mockContext: FolderContext = {
        folderId,
        name: 'My Folder',
        filePaths: ['/uploads/a.pdf', '/uploads/b.txt'],
        contextPrompt: 'You have access to the following files:\n- /uploads/a.pdf\n- /uploads/b.txt'
      };

      service.getFolderContext(folderId).subscribe(context => {
        expect(context.folderId).toBe(folderId);
        expect(context.filePaths.length).toBe(2);
        expect(context.contextPrompt).toContain('/uploads/a.pdf');
      });

      const req = httpMock.expectOne(r => r.url.endsWith(`/folders/${folderId}/context`));
      expect(req.request.method).toBe('GET');
      req.flush(mockContext);
    });

    it('should handle error when context is not available', () => {
      service.getFolderContext('empty-folder').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/folders/empty-folder/context'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. State management — selectFolder(), getSelectedFolder(), clearSelection()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('selectFolder()', () => {
    it('should update selectedFolder$ when a folder is selected', () => {
      const folder = makeFolder({ folderId: 'folder-001', name: 'Selected Folder' });
      let emitted: ChatFolder | null = null;
      service.selectedFolder$.subscribe(f => (emitted = f));

      service.selectFolder(folder);

      expect((emitted as ChatFolder | null)?.folderId).toBe('folder-001');
      expect((emitted as ChatFolder | null)?.name).toBe('Selected Folder');
    });

    it('should update selectedFolder$ to null when null is passed', () => {
      const folder = makeFolder();
      service.selectFolder(folder);

      let emitted: ChatFolder | null = folder;
      service.selectedFolder$.subscribe(f => (emitted = f));

      service.selectFolder(null);

      expect(emitted).toBeNull();
    });
  });

  describe('getSelectedFolder()', () => {
    it('should return null initially', () => {
      expect(service.getSelectedFolder()).toBeNull();
    });

    it('should return selected folder after selectFolder is called', () => {
      const folder = makeFolder({ folderId: 'folder-sync' });
      service.selectFolder(folder);
      expect(service.getSelectedFolder()?.folderId).toBe('folder-sync');
    });
  });

  describe('clearSelection()', () => {
    it('should reset selectedFolder$ to null', () => {
      const folder = makeFolder({ folderId: 'folder-001' });
      service.selectFolder(folder);

      let emitted: ChatFolder | null = folder;
      service.selectedFolder$.subscribe(f => (emitted = f));

      service.clearSelection();

      expect(emitted).toBeNull();
      expect(service.getSelectedFolder()).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 13. getCachedFolders()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getCachedFolders()', () => {
    it('should return empty array before any folders are fetched', () => {
      expect(service.getCachedFolders()).toEqual([]);
    });

    it('should return folders from cache after getFolders() resolves', () => {
      const mockFolders = [makeFolder({ folderId: 'f-1' }), makeFolder({ folderId: 'f-2' })];

      service.getFolders().subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/folders'));
      req.flush(mockFolders);

      const cached = service.getCachedFolders();
      expect(cached.length).toBe(2);
      expect(cached[0].folderId).toBe('f-1');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 14. folders$ BehaviorSubject state
  // ─────────────────────────────────────────────────────────────────────────────

  describe('folders$', () => {
    it('should emit the initial empty array', (done) => {
      service.folders$.subscribe(folders => {
        expect(folders).toEqual([]);
        done();
      });
    });

    it('should emit updated list after createFolder', () => {
      const request: CreateFolderRequest = { name: 'Test' };
      const newFolder = makeFolder({ folderId: 'folder-test', name: 'Test' });
      const emissions: ChatFolder[][] = [];

      service.folders$.subscribe(folders => emissions.push(folders));

      service.createFolder(request).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/folders'));
      req.flush(newFolder);

      // Last emission should contain the newly created folder
      const lastEmission = emissions[emissions.length - 1];
      expect(lastEmission.some(f => f.folderId === 'folder-test')).toBeTrue();
    });
  });
});
