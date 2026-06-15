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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError, Subject } from 'rxjs';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';

import { FolderSidebarComponent } from './folder-sidebar.component';
import { FolderService } from '../../services/folder.service';
import { ChatFolder } from '../../models/api-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

/** Creates a mock ChatFolder with sensible defaults and optional overrides. */
function mockFolder(overrides: Partial<ChatFolder> = {}): ChatFolder {
  return {
    folderId: 'folder-1',
    name: 'Test Folder',
    description: 'A test folder',
    createdAt: '2025-01-01T00:00:00Z',
    updatedAt: '2025-01-02T00:00:00Z',
    fileCount: 0,
    ...overrides
  };
}

/** Creates all spied services and the TestBed providers. */
function createTestBed() {
  const foldersSubject = new Subject<ChatFolder[]>();
  const selectedFolderSubject = new Subject<ChatFolder | null>();

  const folderServiceSpy = jasmine.createSpyObj<FolderService>('FolderService', [
    'getFolders',
    'createFolder',
    'updateFolder',
    'deleteFolder',
    'selectFolder',
    'clearSelection'
  ], {
    folders$: foldersSubject.asObservable(),
    selectedFolder$: selectedFolderSubject.asObservable()
  });

  const dialogSpy = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

  // Default return values
  folderServiceSpy.getFolders.and.returnValue(of([]));
  folderServiceSpy.createFolder.and.returnValue(of(mockFolder()));
  folderServiceSpy.updateFolder.and.returnValue(of(mockFolder()));
  folderServiceSpy.deleteFolder.and.returnValue(of(undefined as any));

  return {
    folderServiceSpy,
    dialogSpy,
    foldersSubject,
    selectedFolderSubject,
    providers: [
      { provide: FolderService, useValue: folderServiceSpy },
      { provide: MatDialog, useValue: dialogSpy }
    ]
  };
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('FolderSidebarComponent', () => {
  let component: FolderSidebarComponent;
  let fixture: ComponentFixture<FolderSidebarComponent>;
  let spies: ReturnType<typeof createTestBed>;

  beforeEach(async () => {
    spies = createTestBed();

    await TestBed.configureTestingModule({
      declarations: [FolderSidebarComponent],
      imports: [NoopAnimationsModule],
      providers: spies.providers,
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(FolderSidebarComponent);
    component = fixture.componentInstance;
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. COMPONENT CREATION AND INITIALIZATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Component creation and initialization', () => {
    it('should create successfully', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should call loadFolders on init', () => {
      fixture.detectChanges();
      expect(spies.folderServiceSpy.getFolders).toHaveBeenCalledTimes(1);
    });

    it('should initialize with empty folders list', () => {
      fixture.detectChanges();
      expect(component.folders).toEqual([]);
    });

    it('should initialize with no selected folder', () => {
      fixture.detectChanges();
      expect(component.selectedFolder).toBeNull();
    });

    it('should initialize loading as false after loadFolders completes', () => {
      fixture.detectChanges();
      expect(component.isLoading).toBeFalse();
    });

    it('should initialize with no error', () => {
      fixture.detectChanges();
      expect(component.error).toBeNull();
    });

    it('should initialize with form hidden', () => {
      fixture.detectChanges();
      expect(component.showNewFolderForm).toBeFalse();
    });

    it('should populate folders from loadFolders response', () => {
      const folders = [mockFolder({ folderId: 'f-1', name: 'Folder A' })];
      spies.folderServiceSpy.getFolders.and.returnValue(of(folders));

      fixture.detectChanges();

      expect(component.folders.length).toBe(1);
      expect(component.folders[0].name).toBe('Folder A');
    });

    it('should update folders when folders$ emits', () => {
      fixture.detectChanges();
      const updated = [mockFolder({ folderId: 'f-2', name: 'Stream Folder' })];
      spies.foldersSubject.next(updated);

      expect(component.folders.length).toBe(1);
      expect(component.folders[0].name).toBe('Stream Folder');
    });

    it('should update selectedFolder when selectedFolder$ emits', () => {
      fixture.detectChanges();
      const folder = mockFolder({ folderId: 'f-3', name: 'Selected' });
      spies.selectedFolderSubject.next(folder);

      expect(component.selectedFolder?.folderId).toBe('f-3');
    });

    it('should not leak subscriptions after destroy', () => {
      fixture.detectChanges();
      fixture.destroy();

      // Emit after destroy — component should not throw and should not update
      const folderAfterDestroy = mockFolder({ folderId: 'zombie' });
      expect(() => spies.foldersSubject.next([folderAfterDestroy])).not.toThrow();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. loadFolders()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadFolders()', () => {
    it('should set isLoading to true while loading', () => {
      // Use a subject that never emits so loading stays true
      const never$ = new Subject<ChatFolder[]>();
      spies.folderServiceSpy.getFolders.and.returnValue(never$.asObservable());

      fixture.detectChanges();

      expect(component.isLoading).toBeTrue();
    });

    it('should set isLoading to false on success', () => {
      spies.folderServiceSpy.getFolders.and.returnValue(of([mockFolder()]));
      fixture.detectChanges();

      expect(component.isLoading).toBeFalse();
    });

    it('should clear error before loading', () => {
      component.error = 'Previous error';
      spies.folderServiceSpy.getFolders.and.returnValue(of([]));

      component.loadFolders();

      expect(component.error).toBeNull();
    });

    it('should set error and stop loading on failure', () => {
      spies.folderServiceSpy.getFolders.and.returnValue(
        throwError(() => new Error('Network error'))
      );

      fixture.detectChanges();

      expect(component.error).toBe('Network error');
      expect(component.isLoading).toBeFalse();
    });

    it('should fall back to generic message when error has no message', () => {
      spies.folderServiceSpy.getFolders.and.returnValue(
        throwError(() => ({}))
      );

      fixture.detectChanges();

      expect(component.error).toBe('Failed to load folders');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. selectFolder()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('selectFolder()', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should select a folder and emit it', () => {
      const folder = mockFolder({ folderId: 'f-1' });
      const emitted: Array<ChatFolder | null> = [];
      component.folderSelected.subscribe(f => emitted.push(f));

      component.selectFolder(folder);

      expect(component.selectedFolder).toBe(folder);
      expect(emitted.length).toBe(1);
      expect(emitted[0]).toBe(folder);
    });

    it('should call folderService.selectFolder with the folder', () => {
      const folder = mockFolder();
      component.selectFolder(folder);

      expect(spies.folderServiceSpy.selectFolder).toHaveBeenCalledWith(folder);
    });

    it('should deselect when clicking the already-selected folder', () => {
      const folder = mockFolder({ folderId: 'f-same' });
      component.selectedFolder = folder;
      const emitted: Array<ChatFolder | null> = [];
      component.folderSelected.subscribe(f => emitted.push(f));

      component.selectFolder(folder);

      expect(component.selectedFolder).toBeNull();
      expect(emitted[0]).toBeNull();
    });

    it('should call folderService.selectFolder(null) when deselecting', () => {
      const folder = mockFolder({ folderId: 'f-same' });
      component.selectedFolder = folder;

      component.selectFolder(folder);

      expect(spies.folderServiceSpy.selectFolder).toHaveBeenCalledWith(null);
    });

    it('should emit null when deselecting', () => {
      const folder = mockFolder({ folderId: 'f-same' });
      component.selectedFolder = folder;
      const emitted: Array<ChatFolder | null> = [];
      component.folderSelected.subscribe(f => emitted.push(f));

      component.selectFolder(folder);

      expect(emitted[0]).toBeNull();
    });

    it('should switch selection from one folder to another', () => {
      const folderA = mockFolder({ folderId: 'f-a', name: 'A' });
      const folderB = mockFolder({ folderId: 'f-b', name: 'B' });

      component.selectFolder(folderA);
      expect(component.selectedFolder?.folderId).toBe('f-a');

      component.selectFolder(folderB);
      expect(component.selectedFolder?.folderId).toBe('f-b');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. clearSelection()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('clearSelection()', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should set selectedFolder to null', () => {
      component.selectedFolder = mockFolder();
      component.clearSelection();

      expect(component.selectedFolder).toBeNull();
    });

    it('should call folderService.clearSelection()', () => {
      component.clearSelection();
      expect(spies.folderServiceSpy.clearSelection).toHaveBeenCalledTimes(1);
    });

    it('should emit null via folderSelected output', () => {
      const emitted: Array<ChatFolder | null> = [];
      component.folderSelected.subscribe(f => emitted.push(f));

      component.clearSelection();

      expect(emitted.length).toBe(1);
      expect(emitted[0]).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. createFolder()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('createFolder()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      component.showNewFolderForm = true;
    });

    it('should do nothing when folder name is empty', () => {
      component.newFolderName = '';
      component.createFolder();

      expect(spies.folderServiceSpy.createFolder).not.toHaveBeenCalled();
    });

    it('should do nothing when folder name is only whitespace', () => {
      component.newFolderName = '   ';
      component.createFolder();

      expect(spies.folderServiceSpy.createFolder).not.toHaveBeenCalled();
    });

    it('should call folderService.createFolder with trimmed name', () => {
      component.newFolderName = '  My Folder  ';
      component.newFolderDescription = 'A description';

      component.createFolder();

      expect(spies.folderServiceSpy.createFolder).toHaveBeenCalledWith(
        jasmine.objectContaining({ name: 'My Folder' })
      );
    });

    it('should pass description trimmed in the request', () => {
      component.newFolderName = 'Folder';
      component.newFolderDescription = '  desc  ';

      component.createFolder();

      expect(spies.folderServiceSpy.createFolder).toHaveBeenCalledWith(
        jasmine.objectContaining({ description: 'desc' })
      );
    });

    it('should pass undefined description when description is blank', () => {
      component.newFolderName = 'Folder';
      component.newFolderDescription = '   ';

      component.createFolder();

      expect(spies.folderServiceSpy.createFolder).toHaveBeenCalledWith(
        jasmine.objectContaining({ description: undefined })
      );
    });

    it('should set isCreating to true while waiting for response', () => {
      const never$ = new Subject<ChatFolder>();
      spies.folderServiceSpy.createFolder.and.returnValue(never$.asObservable());
      component.newFolderName = 'Folder';

      component.createFolder();

      expect(component.isCreating).toBeTrue();
    });

    it('should hide the form and reset fields on success', () => {
      const newFolder = mockFolder({ folderId: 'f-new', name: 'My Folder' });
      spies.folderServiceSpy.createFolder.and.returnValue(of(newFolder));
      component.newFolderName = 'My Folder';
      component.newFolderDescription = 'desc';

      component.createFolder();

      expect(component.showNewFolderForm).toBeFalse();
      expect(component.newFolderName).toBe('');
      expect(component.newFolderDescription).toBe('');
      expect(component.isCreating).toBeFalse();
    });

    it('should auto-select the newly created folder', () => {
      const newFolder = mockFolder({ folderId: 'f-new', name: 'New' });
      spies.folderServiceSpy.createFolder.and.returnValue(of(newFolder));
      component.newFolderName = 'New';

      component.createFolder();

      expect(component.selectedFolder?.folderId).toBe('f-new');
    });

    it('should emit the new folder via folderSelected after creation', () => {
      const newFolder = mockFolder({ folderId: 'f-new', name: 'New' });
      spies.folderServiceSpy.createFolder.and.returnValue(of(newFolder));
      const emitted: Array<ChatFolder | null> = [];
      component.folderSelected.subscribe(f => emitted.push(f));
      component.newFolderName = 'New';

      component.createFolder();

      expect(emitted.length).toBeGreaterThan(0);
      expect(emitted[emitted.length - 1]?.folderId).toBe('f-new');
    });

    it('should set error and stop creating on failure', () => {
      spies.folderServiceSpy.createFolder.and.returnValue(
        throwError(() => new Error('Server error'))
      );
      component.newFolderName = 'Folder';

      component.createFolder();

      expect(component.error).toBe('Server error');
      expect(component.isCreating).toBeFalse();
    });

    it('should fall back to generic create error message', () => {
      spies.folderServiceSpy.createFolder.and.returnValue(
        throwError(() => ({}))
      );
      component.newFolderName = 'Folder';

      component.createFolder();

      expect(component.error).toBe('Failed to create folder');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. showCreateForm() / cancelCreate()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('showCreateForm() and cancelCreate()', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('showCreateForm should show the form and reset fields', () => {
      component.newFolderName = 'old';
      component.newFolderDescription = 'old desc';

      component.showCreateForm();

      expect(component.showNewFolderForm).toBeTrue();
      expect(component.newFolderName).toBe('');
      expect(component.newFolderDescription).toBe('');
    });

    it('cancelCreate should hide the form and clear fields', () => {
      component.showNewFolderForm = true;
      component.newFolderName = 'typed';
      component.newFolderDescription = 'typed desc';

      component.cancelCreate();

      expect(component.showNewFolderForm).toBeFalse();
      expect(component.newFolderName).toBe('');
      expect(component.newFolderDescription).toBe('');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. saveEdit()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('saveEdit()', () => {
    const folder = mockFolder({ folderId: 'f-edit', name: 'Original' });

    beforeEach(() => {
      fixture.detectChanges();
      component.editingFolder = folder;
      component.editName = 'Updated Name';
      component.editDescription = 'Updated desc';
    });

    it('should do nothing when editingFolder is null', () => {
      component.editingFolder = null;
      component.saveEdit();

      expect(spies.folderServiceSpy.updateFolder).not.toHaveBeenCalled();
    });

    it('should do nothing when editName is blank', () => {
      component.editName = '   ';
      component.saveEdit();

      expect(spies.folderServiceSpy.updateFolder).not.toHaveBeenCalled();
    });

    it('should call updateFolder with trimmed name', () => {
      component.editName = '  New Name  ';
      spies.folderServiceSpy.updateFolder.and.returnValue(of(mockFolder({ name: 'New Name' })));

      component.saveEdit();

      expect(spies.folderServiceSpy.updateFolder).toHaveBeenCalledWith(
        'f-edit',
        jasmine.objectContaining({ name: 'New Name' })
      );
    });

    it('should call updateFolder with trimmed description', () => {
      component.editDescription = '  new desc  ';
      spies.folderServiceSpy.updateFolder.and.returnValue(of(mockFolder()));

      component.saveEdit();

      expect(spies.folderServiceSpy.updateFolder).toHaveBeenCalledWith(
        'f-edit',
        jasmine.objectContaining({ description: 'new desc' })
      );
    });

    it('should pass undefined description when editDescription is blank', () => {
      component.editDescription = '';
      spies.folderServiceSpy.updateFolder.and.returnValue(of(mockFolder()));

      component.saveEdit();

      expect(spies.folderServiceSpy.updateFolder).toHaveBeenCalledWith(
        'f-edit',
        jasmine.objectContaining({ description: undefined })
      );
    });

    it('should clear editingFolder and reset fields on success', () => {
      spies.folderServiceSpy.updateFolder.and.returnValue(of(mockFolder()));

      component.saveEdit();

      expect(component.editingFolder).toBeNull();
      expect(component.editName).toBe('');
      expect(component.editDescription).toBe('');
    });

    it('should set error on failure and leave editingFolder set', () => {
      spies.folderServiceSpy.updateFolder.and.returnValue(
        throwError(() => new Error('Update failed'))
      );

      component.saveEdit();

      expect(component.error).toBe('Update failed');
      // editingFolder remains set so user can retry
    });

    it('should fall back to generic update error message', () => {
      spies.folderServiceSpy.updateFolder.and.returnValue(
        throwError(() => ({}))
      );

      component.saveEdit();

      expect(component.error).toBe('Failed to update folder');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. startEdit() / cancelEdit()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('startEdit() and cancelEdit()', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('startEdit should set editingFolder and populate edit fields', () => {
      const folder = mockFolder({ folderId: 'f-1', name: 'My Folder', description: 'My desc' });
      const event = new Event('click');

      component.startEdit(folder, event);

      expect(component.editingFolder).toBe(folder);
      expect(component.editName).toBe('My Folder');
      expect(component.editDescription).toBe('My desc');
    });

    it('startEdit should use empty string when folder has no description', () => {
      const folder = mockFolder({ description: undefined });
      const event = new Event('click');

      component.startEdit(folder, event);

      expect(component.editDescription).toBe('');
    });

    it('startEdit should stop event propagation', () => {
      const folder = mockFolder();
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');

      component.startEdit(folder, event);

      expect(event.stopPropagation).toHaveBeenCalled();
    });

    it('cancelEdit should clear editingFolder and reset fields', () => {
      component.editingFolder = mockFolder();
      component.editName = 'something';
      component.editDescription = 'something else';

      component.cancelEdit();

      expect(component.editingFolder).toBeNull();
      expect(component.editName).toBe('');
      expect(component.editDescription).toBe('');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. deleteFolder()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('deleteFolder()', () => {
    let afterClosedSubject: Subject<boolean | undefined>;
    let dialogRefMock: Partial<MatDialogRef<any>>;

    beforeEach(() => {
      fixture.detectChanges();
      afterClosedSubject = new Subject<boolean | undefined>();
      dialogRefMock = {
        afterClosed: () => afterClosedSubject.asObservable()
      };
      spies.dialogSpy.open.and.returnValue(dialogRefMock as MatDialogRef<any>);
    });

    it('should open a ConfirmDialogComponent', () => {
      const folder = mockFolder();
      const event = new Event('click');

      component.deleteFolder(folder, event);

      expect(spies.dialogSpy.open).toHaveBeenCalledTimes(1);
    });

    it('should open dialog with correct title and confirmColor', () => {
      const folder = mockFolder({ name: 'Docs' });
      const event = new Event('click');

      component.deleteFolder(folder, event);

      const dialogData = spies.dialogSpy.open.calls.mostRecent().args[1]?.data as any;
      expect(dialogData.title).toBe('Delete Folder');
      expect(dialogData.confirmColor).toBe('warn');
    });

    it('should include folder name in dialog message', () => {
      const folder = mockFolder({ name: 'Important Files' });
      const event = new Event('click');

      component.deleteFolder(folder, event);

      const dialogData = spies.dialogSpy.open.calls.mostRecent().args[1]?.data as any;
      expect(dialogData.message).toContain('Important Files');
    });

    it('should call folderService.deleteFolder when confirmed', () => {
      const folder = mockFolder({ folderId: 'f-del' });
      const event = new Event('click');

      component.deleteFolder(folder, event);
      afterClosedSubject.next(true);

      expect(spies.folderServiceSpy.deleteFolder).toHaveBeenCalledWith('f-del');
    });

    it('should NOT call folderService.deleteFolder when cancelled', () => {
      const folder = mockFolder({ folderId: 'f-del' });
      const event = new Event('click');

      component.deleteFolder(folder, event);
      afterClosedSubject.next(false);

      expect(spies.folderServiceSpy.deleteFolder).not.toHaveBeenCalled();
    });

    it('should NOT call folderService.deleteFolder when dialog dismissed (undefined)', () => {
      const folder = mockFolder({ folderId: 'f-del' });
      const event = new Event('click');

      component.deleteFolder(folder, event);
      afterClosedSubject.next(undefined);

      expect(spies.folderServiceSpy.deleteFolder).not.toHaveBeenCalled();
    });

    it('should clear selection when the deleted folder was selected', () => {
      const folder = mockFolder({ folderId: 'f-del' });
      component.selectedFolder = folder;
      spies.folderServiceSpy.deleteFolder.and.returnValue(of(undefined as any));
      const event = new Event('click');

      component.deleteFolder(folder, event);
      afterClosedSubject.next(true);

      expect(component.selectedFolder).toBeNull();
      expect(spies.folderServiceSpy.clearSelection).toHaveBeenCalled();
    });

    it('should NOT clear selection when a different folder is deleted', () => {
      const selectedFolder = mockFolder({ folderId: 'f-selected' });
      const targetFolder = mockFolder({ folderId: 'f-del' });
      component.selectedFolder = selectedFolder;
      spies.folderServiceSpy.deleteFolder.and.returnValue(of(undefined as any));
      const event = new Event('click');

      component.deleteFolder(targetFolder, event);
      afterClosedSubject.next(true);

      expect(component.selectedFolder?.folderId).toBe('f-selected');
    });

    it('should set error on delete failure', () => {
      const folder = mockFolder({ folderId: 'f-del' });
      spies.folderServiceSpy.deleteFolder.and.returnValue(
        throwError(() => new Error('Delete failed'))
      );
      const event = new Event('click');

      component.deleteFolder(folder, event);
      afterClosedSubject.next(true);

      expect(component.error).toBe('Delete failed');
    });

    it('should fall back to generic delete error message', () => {
      const folder = mockFolder({ folderId: 'f-del' });
      spies.folderServiceSpy.deleteFolder.and.returnValue(
        throwError(() => ({}))
      );
      const event = new Event('click');

      component.deleteFolder(folder, event);
      afterClosedSubject.next(true);

      expect(component.error).toBe('Failed to delete folder');
    });

    it('should stop event propagation', () => {
      const folder = mockFolder();
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');

      component.deleteFolder(folder, event);

      expect(event.stopPropagation).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. formatDate()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('formatDate()', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should return a non-empty string for a valid ISO date', () => {
      const result = component.formatDate('2025-01-15T12:00:00Z');
      expect(result).toBeTruthy();
      expect(typeof result).toBe('string');
    });

    it('should format date as a locale date string', () => {
      const isoDate = '2025-01-15T12:00:00Z';
      const result = component.formatDate(isoDate);
      const expected = new Date(isoDate).toLocaleDateString();
      expect(result).toBe(expected);
    });

    it('should handle different date strings consistently', () => {
      const date1 = component.formatDate('2024-06-01T00:00:00Z');
      const date2 = component.formatDate('2025-12-31T23:59:59Z');
      expect(date1).not.toBe(date2);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. trackByFolderId()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('trackByFolderId()', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should return the folderId of the given folder', () => {
      const folder = mockFolder({ folderId: 'track-123' });
      const result = component.trackByFolderId(0, folder);
      expect(result).toBe('track-123');
    });

    it('should return different IDs for different folders', () => {
      const folderA = mockFolder({ folderId: 'a' });
      const folderB = mockFolder({ folderId: 'b' });

      expect(component.trackByFolderId(0, folderA)).toBe('a');
      expect(component.trackByFolderId(1, folderB)).toBe('b');
    });

    it('should return the same ID regardless of index', () => {
      const folder = mockFolder({ folderId: 'stable' });

      expect(component.trackByFolderId(0, folder)).toBe('stable');
      expect(component.trackByFolderId(99, folder)).toBe('stable');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. @Output() folderSelected EventEmitter
  // ─────────────────────────────────────────────────────────────────────────────

  describe('@Output() folderSelected', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should emit folder when a new folder is selected', () => {
      const folder = mockFolder({ folderId: 'f-emit' });
      let emittedValue: ChatFolder | null | undefined;
      component.folderSelected.subscribe(f => (emittedValue = f));

      component.selectFolder(folder);

      expect(emittedValue).toBe(folder);
    });

    it('should emit null when selection is cleared', () => {
      component.selectedFolder = mockFolder();
      let emittedValue: ChatFolder | null | undefined = undefined;
      component.folderSelected.subscribe(f => (emittedValue = f));

      component.clearSelection();

      expect(emittedValue).toBeNull();
    });

    it('should emit null when the same folder is clicked to deselect', () => {
      const folder = mockFolder({ folderId: 'f-toggle' });
      component.selectedFolder = folder;
      let lastEmit: ChatFolder | null | undefined = undefined;
      component.folderSelected.subscribe(f => (lastEmit = f));

      component.selectFolder(folder);

      expect(lastEmit).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 13. ERROR HANDLING — GENERAL EDGE CASES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Error handling edge cases', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should reset error to null on successful loadFolders after a previous error', () => {
      component.error = 'Old error';
      spies.folderServiceSpy.getFolders.and.returnValue(of([mockFolder()]));

      component.loadFolders();

      expect(component.error).toBeNull();
    });

    it('should not overwrite existing error on successful createFolder (error only set on failure)', () => {
      component.error = 'Old error';
      component.newFolderName = 'New';
      spies.folderServiceSpy.createFolder.and.returnValue(of(mockFolder()));

      component.createFolder();

      // Component does not clear error on success, only sets it on failure
      expect(component.error).toBe('Old error');
    });

    it('saveEdit should not call updateFolder when editingFolder is null even with a name', () => {
      component.editingFolder = null;
      component.editName = 'Valid Name';

      component.saveEdit();

      expect(spies.folderServiceSpy.updateFolder).not.toHaveBeenCalled();
    });
  });
});
