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
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, throwError, Subject } from 'rxjs';

import { ChunkManagerComponent } from './chunk-manager.component';
import { ChunkManagerService } from '../../services/chunk-manager.service';
import {
  ChunkSummary,
  ChunkDetail,
  ChunkEditDetail,
  ChunkListResponse,
  SourceListResponse,
  OperationResponse,
  DuplicateAnalysisResponse,
  DeduplicationResult,
  ClearTokenResponse,
  ChunkUpdateResponse
} from '../../models/chunk-manager.models';
import { PageEvent } from '@angular/material/paginator';

// ─────────────────────────────────────────────────────────────────────────────
// Test helpers
// ─────────────────────────────────────────────────────────────────────────────

function makeChunk(overrides: Partial<ChunkSummary> = {}): ChunkSummary {
  return {
    id: 'chunk-1',
    preview: 'Hello world preview',
    contentLength: 11,
    inKeywordIndex: true,
    inVectorStore: true,
    ...overrides
  };
}

function makeChunkDetail(overrides: Partial<ChunkDetail> = {}): ChunkDetail {
  return {
    id: 'chunk-1',
    content: 'Hello world',
    contentLength: 11,
    metadata: { source: 'test.pdf' },
    inKeywordIndex: true,
    inVectorStore: true,
    ...overrides
  };
}

function makeChunkEditDetail(overrides: Partial<ChunkEditDetail> = {}): ChunkEditDetail {
  return {
    id: 'chunk-1',
    content: 'Hello world',
    contentLength: 11,
    metadata: {},
    inKeywordIndex: true,
    inVectorStore: true,
    semanticType: 'TEXT',
    sourceTitle: 'Test Doc',
    sourceAuthor: 'Author',
    sourceDate: '2025-01-01',
    sourceUrl: 'https://example.com',
    entities: [],
    ...overrides
  };
}

function makeChunkListResponse(chunks: ChunkSummary[] = [], totalCount = 0): ChunkListResponse {
  return {
    chunks,
    offset: 0,
    limit: 20,
    totalCount,
    pageCount: 1
  };
}

function makeSourceListResponse(): SourceListResponse {
  return {
    sources: [
      { sourceId: 'src-1', filename: 'doc1.pdf', chunkCount: 5, keywordChunkCount: 5, vectorChunkCount: 5 }
    ],
    totalSources: 1
  };
}

function makeOperationResponse(overrides: Partial<OperationResponse> = {}): OperationResponse {
  return { success: true, message: 'Done', affectedCount: 1, ...overrides };
}

// ─────────────────────────────────────────────────────────────────────────────
// Spy factories
// ─────────────────────────────────────────────────────────────────────────────

function createChunkManagerServiceSpy(): jasmine.SpyObj<ChunkManagerService> {
  const spy = jasmine.createSpyObj<ChunkManagerService>('ChunkManagerService', [
    'listChunks',
    'listSources',
    'getChunk',
    'getChunkForEdit',
    'updateChunk',
    'deleteChunk',
    'deleteChunks',
    'deleteBySource',
    'generateClearToken',
    'clearAll',
    'analyzeDuplicates',
    'deduplicate',
    'downloadExport'
  ]);
  // Default stubs
  spy.listChunks.and.returnValue(of(makeChunkListResponse()));
  spy.listSources.and.returnValue(of(makeSourceListResponse()));
  spy.getChunk.and.returnValue(of(makeChunkDetail()));
  spy.getChunkForEdit.and.returnValue(of(makeChunkEditDetail()));
  spy.updateChunk.and.returnValue(of({ success: true, message: 'Updated' } as ChunkUpdateResponse));
  spy.deleteChunk.and.returnValue(of(makeOperationResponse()));
  spy.deleteChunks.and.returnValue(of(makeOperationResponse({ affectedCount: 2 })));
  spy.deleteBySource.and.returnValue(of(makeOperationResponse()));
  spy.generateClearToken.and.returnValue(of({ token: 'tok-123', expiresIn: 60, message: 'Generated' } as ClearTokenResponse));
  spy.clearAll.and.returnValue(of(makeOperationResponse({ message: 'Cleared' })));
  spy.analyzeDuplicates.and.returnValue(of({
    strategy: 'content_hash',
    totalDuplicateGroups: 0,
    totalDuplicateChunks: 0,
    chunksToRemove: 0,
    groups: []
  } as DuplicateAnalysisResponse));
  spy.deduplicate.and.returnValue(of({
    strategy: 'content_hash',
    duplicateGroupsFound: 0,
    chunksRemoved: 0,
    chunksKept: 0,
    success: true,
    message: 'Done'
  } as DeduplicationResult));
  spy.downloadExport.and.returnValue(of(new Blob(['content'])));
  return spy;
}

function createDialogSpy(result: any = false): jasmine.SpyObj<MatDialog> {
  const afterClosedSubject = new Subject<any>();
  const dialogRefSpy = jasmine.createSpyObj<MatDialogRef<any>>('MatDialogRef', ['afterClosed']);
  dialogRefSpy.afterClosed.and.returnValue(of(result));
  const dialogSpy = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
  dialogSpy.open.and.returnValue(dialogRefSpy as any);
  return dialogSpy;
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

describe('ChunkManagerComponent', () => {
  let component: ChunkManagerComponent;
  let fixture: ComponentFixture<ChunkManagerComponent>;
  let serviceSpy: jasmine.SpyObj<ChunkManagerService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  beforeEach(async () => {
    serviceSpy = createChunkManagerServiceSpy();
    snackBarSpy = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    dialogSpy = createDialogSpy(true); // dialogs confirm by default

    await TestBed.configureTestingModule({
      declarations: [ChunkManagerComponent],
      imports: [NoopAnimationsModule],
      providers: [
        { provide: ChunkManagerService, useValue: serviceSpy },
        { provide: MatSnackBar, useValue: snackBarSpy },
        { provide: MatDialog, useValue: dialogSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(ChunkManagerComponent);
    component = fixture.componentInstance;
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 1. Component creation and init
  // ─────────────────────────────────────────────────────────────────────────

  describe('Component creation', () => {
    it('should create the component', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should call loadChunks and loadSources on init', () => {
      fixture.detectChanges();
      expect(serviceSpy.listChunks).toHaveBeenCalledWith(0, 20, undefined);
      expect(serviceSpy.listSources).toHaveBeenCalled();
    });

    it('should initialize with correct default state', () => {
      expect(component.isLoading).toBeFalse();
      expect(component.chunks).toEqual([]);
      expect(component.totalCount).toBe(0);
      expect(component.pageIndex).toBe(0);
      expect(component.pageSize).toBe(20);
      expect(component.selectedSourceId).toBe('');
      expect(component.selectedChunk).toBeNull();
      expect(component.isEditMode).toBeFalse();
      expect(component.showDedupDialog).toBeFalse();
      expect(component.clearToken).toBeNull();
    });

    it('should populate chunks after loadChunks response', fakeAsync(() => {
      const chunks = [makeChunk(), makeChunk({ id: 'chunk-2', preview: 'Second' })];
      serviceSpy.listChunks.and.returnValue(of(makeChunkListResponse(chunks, 2)));
      fixture.detectChanges();
      tick();
      expect(component.chunks.length).toBe(2);
      expect(component.totalCount).toBe(2);
      expect(component.isLoading).toBeFalse();
    }));

    it('should populate sources after loadSources response', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(component.sources.length).toBe(1);
      expect(component.sources[0].sourceId).toBe('src-1');
    }));

    it('should handle loadChunks error gracefully', fakeAsync(() => {
      serviceSpy.listChunks.and.returnValue(throwError(() => new Error('network')));
      fixture.detectChanges();
      tick();
      expect(component.isLoading).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to load chunks', 'Close', jasmine.any(Object));
    }));

    it('should handle loadSources error gracefully', fakeAsync(() => {
      serviceSpy.listSources.and.returnValue(throwError(() => new Error('network')));
      fixture.detectChanges();
      tick();
      // No snack bar for sources error - just logs
      expect(component.sources).toEqual([]);
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 2. Pagination
  // ─────────────────────────────────────────────────────────────────────────

  describe('Pagination', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('should update pageIndex and pageSize on page change and reload chunks', fakeAsync(() => {
      const event: PageEvent = { pageIndex: 2, pageSize: 50, length: 200 };
      component.onPageChange(event);
      tick();
      expect(component.pageIndex).toBe(2);
      expect(component.pageSize).toBe(50);
      expect(serviceSpy.listChunks).toHaveBeenCalledWith(100, 50, undefined);
    }));

    it('should reset to page 0 when source filter changes', fakeAsync(() => {
      component.pageIndex = 3;
      component.selectedSourceId = 'src-1';
      component.onSourceFilterChange();
      tick();
      expect(component.pageIndex).toBe(0);
      expect(serviceSpy.listChunks).toHaveBeenCalledWith(0, 20, 'src-1');
    }));

    it('should pass sourceId to listChunks when selectedSourceId is set', fakeAsync(() => {
      component.selectedSourceId = 'src-2';
      component.loadChunks();
      tick();
      expect(serviceSpy.listChunks).toHaveBeenCalledWith(0, 20, 'src-2');
    }));

    it('should pass undefined sourceId when selectedSourceId is empty', fakeAsync(() => {
      component.selectedSourceId = '';
      component.loadChunks();
      tick();
      expect(serviceSpy.listChunks).toHaveBeenCalledWith(0, 20, undefined);
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 3. Selection model
  // ─────────────────────────────────────────────────────────────────────────

  describe('Selection model', () => {
    const chunk1 = makeChunk({ id: 'c1' });
    const chunk2 = makeChunk({ id: 'c2' });

    beforeEach(fakeAsync(() => {
      serviceSpy.listChunks.and.returnValue(of(makeChunkListResponse([chunk1, chunk2], 2)));
      fixture.detectChanges();
      tick();
    }));

    it('isAllSelected() should return false when nothing selected', () => {
      expect(component.isAllSelected()).toBeFalse();
    });

    it('isAllSelected() should return true when all rows selected', () => {
      component.selection.select(chunk1, chunk2);
      expect(component.isAllSelected()).toBeTrue();
    });

    it('masterToggle() should select all when none selected', () => {
      component.masterToggle();
      expect(component.selection.selected.length).toBe(2);
    });

    it('masterToggle() should clear all when all selected', () => {
      component.selection.select(chunk1, chunk2);
      component.masterToggle();
      expect(component.selection.selected.length).toBe(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 4. Chunk detail view
  // ─────────────────────────────────────────────────────────────────────────

  describe('Chunk detail view', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('viewChunk() should call getChunk and set selectedChunk', fakeAsync(() => {
      const chunk = makeChunk();
      const detail = makeChunkDetail();
      serviceSpy.getChunk.and.returnValue(of(detail));

      component.viewChunk(chunk);
      tick();

      expect(serviceSpy.getChunk).toHaveBeenCalledWith('chunk-1');
      expect(component.selectedChunk).toEqual(detail);
      expect(component.isLoadingDetail).toBeFalse();
    }));

    it('viewChunk() should show error on failure', fakeAsync(() => {
      serviceSpy.getChunk.and.returnValue(throwError(() => new Error('not found')));
      component.viewChunk(makeChunk());
      tick();
      expect(component.isLoadingDetail).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to load chunk details', 'Close', jasmine.any(Object));
    }));

    it('closeDetail() should clear selectedChunk and exit edit mode', () => {
      component.selectedChunk = makeChunkDetail();
      component.isEditMode = true;
      component.closeDetail();
      expect(component.selectedChunk).toBeNull();
      expect(component.isEditMode).toBeFalse();
      expect(component.editChunk).toBeNull();
    });

    it('toggleMarkdownView() should toggle showRenderedMarkdown', () => {
      expect(component.showRenderedMarkdown).toBeFalse();
      component.toggleMarkdownView();
      expect(component.showRenderedMarkdown).toBeTrue();
      component.toggleMarkdownView();
      expect(component.showRenderedMarkdown).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 5. Edit mode
  // ─────────────────────────────────────────────────────────────────────────

  describe('Edit mode', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      component.selectedChunk = makeChunkDetail();
    }));

    it('enterEditMode() should call getChunkForEdit and populate edit fields', fakeAsync(() => {
      const editDetail = makeChunkEditDetail({
        content: 'Editable content',
        semanticType: 'DEFINITION',
        sourceTitle: 'My Doc',
        entities: [{ name: 'Angular', type: 'TECHNOLOGY' }]
      });
      serviceSpy.getChunkForEdit.and.returnValue(of(editDetail));

      component.enterEditMode();
      tick();

      expect(serviceSpy.getChunkForEdit).toHaveBeenCalledWith('chunk-1');
      expect(component.isEditMode).toBeTrue();
      expect(component.editContent).toBe('Editable content');
      expect(component.editSemanticType).toBe('DEFINITION');
      expect(component.editSourceTitle).toBe('My Doc');
      expect(component.editEntities.length).toBe(1);
      expect(component.isLoadingDetail).toBeFalse();
    }));

    it('enterEditMode() should do nothing if selectedChunk is null', () => {
      component.selectedChunk = null;
      component.enterEditMode();
      expect(serviceSpy.getChunkForEdit).not.toHaveBeenCalled();
    });

    it('enterEditMode() should show error on failure', fakeAsync(() => {
      serviceSpy.getChunkForEdit.and.returnValue(throwError(() => new Error('error')));
      component.enterEditMode();
      tick();
      expect(component.isLoadingDetail).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to load chunk for editing', 'Close', jasmine.any(Object));
    }));

    it('cancelEdit() should reset edit state', () => {
      component.isEditMode = true;
      component.editChunk = makeChunkEditDetail();
      component.editEntities = [{ name: 'x', type: 'CONCEPT' }];
      component.cancelEdit();
      expect(component.isEditMode).toBeFalse();
      expect(component.editChunk).toBeNull();
      expect(component.editEntities.length).toBe(0);
    });

    it('saveChunk() should do nothing if editChunk is null', () => {
      component.editChunk = null;
      component.saveChunk();
      expect(serviceSpy.updateChunk).not.toHaveBeenCalled();
    });

    it('saveChunk() should call updateChunk with request and show success', fakeAsync(() => {
      component.editChunk = makeChunkEditDetail();
      component.editContent = 'New content';
      component.editSemanticType = 'CODE';
      component.editSourceTitle = 'Title';
      component.editSourceAuthor = '';
      component.editSourceDate = '';
      component.editSourceUrl = '';
      component.editEntities = [];

      const updateResponse: ChunkUpdateResponse = {
        success: true,
        message: 'Chunk updated successfully',
        updatedChunk: makeChunkEditDetail({ content: 'New content' })
      };
      serviceSpy.updateChunk.and.returnValue(of(updateResponse));
      component.selectedChunk = makeChunkDetail();

      component.saveChunk();
      tick();

      expect(serviceSpy.updateChunk).toHaveBeenCalledWith('chunk-1', jasmine.objectContaining({
        content: 'New content',
        semanticType: 'CODE'
      }));
      expect(snackBarSpy.open).toHaveBeenCalledWith('Chunk updated successfully', 'Close', jasmine.any(Object));
      expect(component.isEditMode).toBeFalse();
    }));

    it('saveChunk() should show error message when success=false', fakeAsync(() => {
      component.editChunk = makeChunkEditDetail();
      component.selectedChunk = makeChunkDetail();
      serviceSpy.updateChunk.and.returnValue(of({ success: false, message: 'Validation failed' }));

      component.saveChunk();
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Validation failed', 'Close', jasmine.any(Object));
    }));

    it('saveChunk() should handle HTTP error', fakeAsync(() => {
      component.editChunk = makeChunkEditDetail();
      component.selectedChunk = makeChunkDetail();
      serviceSpy.updateChunk.and.returnValue(throwError(() => new Error('server error')));

      component.saveChunk();
      tick();

      expect(component.isSaving).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to save chunk', 'Close', jasmine.any(Object));
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 6. Entity management
  // ─────────────────────────────────────────────────────────────────────────

  describe('Entity management', () => {
    it('addEntity() should add entity to editEntities and clear newEntityName', () => {
      component.newEntityName = '  Angular  ';
      component.newEntityType = 'TECHNOLOGY';
      component.addEntity();
      expect(component.editEntities.length).toBe(1);
      expect(component.editEntities[0]).toEqual({ name: 'Angular', type: 'TECHNOLOGY' });
      expect(component.newEntityName).toBe('');
    });

    it('addEntity() should do nothing when newEntityName is empty', () => {
      component.newEntityName = '   ';
      component.addEntity();
      expect(component.editEntities.length).toBe(0);
    });

    it('removeEntity() should remove entity at given index', () => {
      component.editEntities = [
        { name: 'A', type: 'CONCEPT' },
        { name: 'B', type: 'PERSON' },
        { name: 'C', type: 'LOCATION' }
      ];
      component.removeEntity(1);
      expect(component.editEntities.length).toBe(2);
      expect(component.editEntities[0].name).toBe('A');
      expect(component.editEntities[1].name).toBe('C');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 7. Label helpers
  // ─────────────────────────────────────────────────────────────────────────

  describe('Label helpers', () => {
    it('getSemanticTypeLabel() should format WORK_OF_ART to "Work Of Art"', () => {
      expect(component.getSemanticTypeLabel('WORK_OF_ART')).toBe('Work Of Art');
    });

    it('getSemanticTypeLabel() should format TEXT to "Text"', () => {
      expect(component.getSemanticTypeLabel('TEXT')).toBe('Text');
    });

    it('getEntityTypeLabel() should format WORK_OF_ART to "Work Of Art"', () => {
      expect(component.getEntityTypeLabel('WORK_OF_ART')).toBe('Work Of Art');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 8. Delete operations
  // ─────────────────────────────────────────────────────────────────────────

  describe('Delete operations', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('deleteChunk() should open confirm dialog and call deleteChunk on confirm', fakeAsync(() => {
      dialogSpy.open.and.returnValue({ afterClosed: () => of(true) } as any);
      serviceSpy.deleteChunk.and.returnValue(of(makeOperationResponse()));
      serviceSpy.listChunks.and.returnValue(of(makeChunkListResponse()));
      serviceSpy.listSources.and.returnValue(of(makeSourceListResponse()));

      component.deleteChunk(makeChunk());
      tick();

      expect(dialogSpy.open).toHaveBeenCalled();
      expect(serviceSpy.deleteChunk).toHaveBeenCalledWith('chunk-1');
      expect(snackBarSpy.open).toHaveBeenCalledWith('Chunk deleted', 'Close', jasmine.any(Object));
    }));

    it('deleteChunk() should NOT call deleteChunk when dialog is cancelled', fakeAsync(() => {
      dialogSpy.open.and.returnValue({ afterClosed: () => of(false) } as any);

      component.deleteChunk(makeChunk());
      tick();

      expect(serviceSpy.deleteChunk).not.toHaveBeenCalled();
    }));

    it('deleteChunk() should handle service error and show error message', fakeAsync(() => {
      dialogSpy.open.and.returnValue({ afterClosed: () => of(true) } as any);
      serviceSpy.deleteChunk.and.returnValue(throwError(() => new Error('delete failed')));

      component.deleteChunk(makeChunk());
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to delete chunk', 'Close', jasmine.any(Object));
    }));

    it('deleteSelected() should show error when no chunks are selected', fakeAsync(() => {
      component.selection.clear();
      component.deleteSelected();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('No chunks selected', 'Close', jasmine.any(Object));
      expect(dialogSpy.open).not.toHaveBeenCalled();
    }));

    it('deleteSelected() should call deleteChunks with selected ids on confirm', fakeAsync(() => {
      const chunk1 = makeChunk({ id: 'c1' });
      const chunk2 = makeChunk({ id: 'c2' });
      component.selection.select(chunk1, chunk2);
      dialogSpy.open.and.returnValue({ afterClosed: () => of(true) } as any);
      serviceSpy.deleteChunks.and.returnValue(of(makeOperationResponse({ affectedCount: 2, message: 'Deleted 2 chunks' })));
      serviceSpy.listChunks.and.returnValue(of(makeChunkListResponse()));
      serviceSpy.listSources.and.returnValue(of(makeSourceListResponse()));

      component.deleteSelected();
      tick();

      expect(serviceSpy.deleteChunks).toHaveBeenCalledWith(['c1', 'c2']);
      expect(snackBarSpy.open).toHaveBeenCalledWith('Deleted 2 chunks', 'Close', jasmine.any(Object));
    }));

    it('deleteBySource() should open dialog and call deleteBySource on confirm', fakeAsync(() => {
      dialogSpy.open.and.returnValue({ afterClosed: () => of(true) } as any);
      serviceSpy.deleteBySource.and.returnValue(of(makeOperationResponse({ message: 'Source deleted' })));
      serviceSpy.listChunks.and.returnValue(of(makeChunkListResponse()));
      serviceSpy.listSources.and.returnValue(of(makeSourceListResponse()));

      component.deleteBySource('src-1');
      tick();

      expect(serviceSpy.deleteBySource).toHaveBeenCalledWith('src-1');
      expect(snackBarSpy.open).toHaveBeenCalledWith('Source deleted', 'Close', jasmine.any(Object));
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 9. Clear all with token
  // ─────────────────────────────────────────────────────────────────────────

  describe('Clear all with token', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('requestClearToken() should call generateClearToken and store token', fakeAsync(() => {
      serviceSpy.generateClearToken.and.returnValue(of({
        token: 'tok-xyz',
        expiresIn: 60,
        message: 'Generated'
      }));

      component.requestClearToken();
      tick();

      expect(component.clearToken).toBe('tok-xyz');
      expect(component.clearTokenExpiry).toBeGreaterThan(Date.now());
      expect(snackBarSpy.open).toHaveBeenCalledWith('Token generated. Valid for 60 seconds.', 'Close', jasmine.any(Object));
    }));

    it('requestClearToken() should show error on failure', fakeAsync(() => {
      serviceSpy.generateClearToken.and.returnValue(throwError(() => new Error('error')));

      component.requestClearToken();
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to generate confirmation token', 'Close', jasmine.any(Object));
    }));

    it('clearAll() should show error when clearToken is null', () => {
      component.clearToken = null;
      component.clearAll();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Please generate a confirmation token first', 'Close', jasmine.any(Object));
    });

    it('clearAll() should show error and nullify token when token expired', () => {
      component.clearToken = 'tok-expired';
      component.clearTokenExpiry = Date.now() - 1000; // in the past
      component.clearAll();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Confirmation token expired. Please generate a new one.', 'Close', jasmine.any(Object));
      expect(component.clearToken).toBeNull();
    });

    it('clearAll() should call clearAll service with token on dialog confirm', fakeAsync(() => {
      component.clearToken = 'tok-valid';
      component.clearTokenExpiry = Date.now() + 60000;
      dialogSpy.open.and.returnValue({ afterClosed: () => of(true) } as any);
      serviceSpy.clearAll.and.returnValue(of(makeOperationResponse({ message: 'All cleared' })));
      serviceSpy.listChunks.and.returnValue(of(makeChunkListResponse()));
      serviceSpy.listSources.and.returnValue(of(makeSourceListResponse()));

      component.clearAll();
      tick();

      expect(serviceSpy.clearAll).toHaveBeenCalledWith('tok-valid');
      expect(component.clearToken).toBeNull();
      expect(snackBarSpy.open).toHaveBeenCalledWith('All cleared', 'Close', jasmine.any(Object));
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 10. Deduplication
  // ─────────────────────────────────────────────────────────────────────────

  describe('Deduplication', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('openDedupDialog() should set showDedupDialog=true and reset state', () => {
      component.duplicateAnalysis = { strategy: 'content_hash', totalDuplicateGroups: 1, totalDuplicateChunks: 2, chunksToRemove: 1, groups: [] };
      component.openDedupDialog();
      expect(component.showDedupDialog).toBeTrue();
      expect(component.duplicateAnalysis).toBeNull();
      expect(component.dedupDryRun).toBeTrue();
    });

    it('closeDedupDialog() should hide dialog and clear analysis', () => {
      component.showDedupDialog = true;
      component.duplicateAnalysis = { strategy: 'content_hash', totalDuplicateGroups: 0, totalDuplicateChunks: 0, chunksToRemove: 0, groups: [] };
      component.closeDedupDialog();
      expect(component.showDedupDialog).toBeFalse();
      expect(component.duplicateAnalysis).toBeNull();
    });

    it('analyzeDuplicates() should call service with dedupStrategy and set result', fakeAsync(() => {
      const mockAnalysis: DuplicateAnalysisResponse = {
        strategy: 'content_hash',
        totalDuplicateGroups: 3,
        totalDuplicateChunks: 7,
        chunksToRemove: 4,
        groups: []
      };
      component.dedupStrategy = 'content_hash';
      serviceSpy.analyzeDuplicates.and.returnValue(of(mockAnalysis));

      component.analyzeDuplicates();
      tick();

      expect(serviceSpy.analyzeDuplicates).toHaveBeenCalledWith('content_hash');
      expect(component.duplicateAnalysis).toEqual(mockAnalysis);
      expect(component.isDeduplicating).toBeFalse();
    }));

    it('analyzeDuplicates() should handle error', fakeAsync(() => {
      serviceSpy.analyzeDuplicates.and.returnValue(throwError(() => new Error('error')));
      component.analyzeDuplicates();
      tick();
      expect(component.isDeduplicating).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to analyze duplicates', 'Close', jasmine.any(Object));
    }));

    it('runDeduplication() should call deduplicate and show success message', fakeAsync(() => {
      component.dedupStrategy = 'content_hash';
      component.dedupKeepPolicy = 'first';
      component.dedupDryRun = true;
      serviceSpy.deduplicate.and.returnValue(of({
        strategy: 'content_hash',
        duplicateGroupsFound: 2,
        chunksRemoved: 0,
        chunksKept: 5,
        success: true,
        message: 'Dry run complete: 2 groups found'
      }));

      component.runDeduplication();
      tick();

      expect(serviceSpy.deduplicate).toHaveBeenCalledWith({
        strategy: 'content_hash',
        keepPolicy: 'first',
        dryRun: true
      });
      expect(snackBarSpy.open).toHaveBeenCalledWith('Dry run complete: 2 groups found', 'Close', jasmine.any(Object));
    }));

    it('runDeduplication() should refresh and close dialog when not dry run', fakeAsync(() => {
      component.dedupDryRun = false;
      component.showDedupDialog = true;
      serviceSpy.deduplicate.and.returnValue(of({
        strategy: 'content_hash',
        duplicateGroupsFound: 2,
        chunksRemoved: 2,
        chunksKept: 3,
        success: true,
        message: 'Done'
      }));
      serviceSpy.listChunks.and.returnValue(of(makeChunkListResponse()));
      serviceSpy.listSources.and.returnValue(of(makeSourceListResponse()));

      component.runDeduplication();
      tick();

      expect(component.showDedupDialog).toBeFalse();
    }));

    it('runDeduplication() should handle error', fakeAsync(() => {
      serviceSpy.deduplicate.and.returnValue(throwError(() => new Error('error')));
      component.runDeduplication();
      tick();
      expect(component.isDeduplicating).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to run deduplication', 'Close', jasmine.any(Object));
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 11. Export
  // ─────────────────────────────────────────────────────────────────────────

  describe('Export', () => {
    beforeEach(() => { fixture.detectChanges(); });

    it('exportAll() should call downloadExport without chunkIds or sourceId', fakeAsync(() => {
      serviceSpy.downloadExport.and.returnValue(of(new Blob(['content'])));
      spyOn(window.URL, 'createObjectURL').and.returnValue('blob:fake-url');
      spyOn(window.URL, 'revokeObjectURL');

      component.exportAll();
      tick();

      expect(serviceSpy.downloadExport).toHaveBeenCalledWith(jasmine.objectContaining({
        includeMetadata: true,
        format: 'markdown'
      }));
      expect(snackBarSpy.open).toHaveBeenCalledWith('Export downloaded', 'Close', jasmine.any(Object));
    }));

    it('exportSelected() should call downloadExport with selected chunk ids', fakeAsync(() => {
      component.selection.select(
        makeChunk({ id: 'c1' }),
        makeChunk({ id: 'c2' })
      );
      serviceSpy.downloadExport.and.returnValue(of(new Blob(['content'])));
      spyOn(window.URL, 'createObjectURL').and.returnValue('blob:fake-url');
      spyOn(window.URL, 'revokeObjectURL');

      component.exportSelected();
      tick();

      expect(serviceSpy.downloadExport).toHaveBeenCalledWith(jasmine.objectContaining({
        chunkIds: ['c1', 'c2']
      }));
    }));

    it('exportBySource() should call downloadExport with sourceId', fakeAsync(() => {
      serviceSpy.downloadExport.and.returnValue(of(new Blob(['content'])));
      spyOn(window.URL, 'createObjectURL').and.returnValue('blob:fake-url');
      spyOn(window.URL, 'revokeObjectURL');

      component.exportBySource('src-1');
      tick();

      expect(serviceSpy.downloadExport).toHaveBeenCalledWith(jasmine.objectContaining({
        sourceId: 'src-1'
      }));
    }));

    it('exportAll() should show error on download failure', fakeAsync(() => {
      serviceSpy.downloadExport.and.returnValue(throwError(() => new Error('download failed')));

      component.exportAll();
      tick();

      expect(component.isExporting).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to export chunks', 'Close', jasmine.any(Object));
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 12. Utility methods
  // ─────────────────────────────────────────────────────────────────────────

  describe('Utility methods', () => {
    it('getSourceDisplay() should return "Unknown" when sourceId is undefined', () => {
      expect(component.getSourceDisplay(undefined)).toBe('Unknown');
    });

    it('getSourceDisplay() should extract filename from path', () => {
      expect(component.getSourceDisplay('/home/user/docs/report.pdf')).toBe('report.pdf');
    });

    it('getSourceDisplay() should extract filename from Windows path', () => {
      expect(component.getSourceDisplay('C:\\Users\\user\\docs\\report.pdf')).toBe('report.pdf');
    });

    it('getSourceDisplay() should truncate long ids that have no path separator', () => {
      const longId = 'a'.repeat(50);
      expect(component.getSourceDisplay(longId).endsWith('...')).toBeTrue();
      expect(component.getSourceDisplay(longId).length).toBe(30);
    });

    it('getSourceDisplay() should return short ids as-is', () => {
      expect(component.getSourceDisplay('short')).toBe('short');
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
  // 13. refresh()
  // ─────────────────────────────────────────────────────────────────────────

  describe('refresh()', () => {
    it('should clear selection, reload chunks and sources', fakeAsync(() => {
      fixture.detectChanges();
      component.selection.select(makeChunk());
      expect(component.selection.selected.length).toBe(1);

      // Reset call counts
      serviceSpy.listChunks.calls.reset();
      serviceSpy.listSources.calls.reset();

      component.refresh();
      tick();

      expect(component.selection.selected.length).toBe(0);
      expect(serviceSpy.listChunks).toHaveBeenCalled();
      expect(serviceSpy.listSources).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 14. ngOnDestroy
  // ─────────────────────────────────────────────────────────────────────────

  describe('ngOnDestroy', () => {
    it('should complete the destroy subject on destroy', () => {
      fixture.detectChanges();
      const destroySpy = spyOn((component as any).destroy$, 'next').and.callThrough();
      const completeSpy = spyOn((component as any).destroy$, 'complete').and.callThrough();
      component.ngOnDestroy();
      expect(destroySpy).toHaveBeenCalled();
      expect(completeSpy).toHaveBeenCalled();
    });
  });
});
