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
import { NO_ERRORS_SCHEMA, SimpleChange } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { NotesPanelComponent } from './notes-panel.component';
import { NoteSyncService } from '../../services/note-sync.service';
import { NoteModel } from '../../models/sync-models';

function makeNote(overrides: Partial<NoteModel> = {}): NoteModel {
  return {
    id: 1,
    factSheetId: 10,
    title: 'Test Note',
    content: 'Test content',
    noteType: 'HUMAN',
    tags: 'tag1,tag2',
    embedded: false,
    createdAt: '2025-01-01T00:00:00Z',
    updatedAt: '2025-01-01T00:00:00Z',
    ...overrides
  };
}

describe('NotesPanelComponent', () => {
  let component: NotesPanelComponent;
  let fixture: ComponentFixture<NotesPanelComponent>;
  let noteSyncServiceSpy: jasmine.SpyObj<NoteSyncService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  beforeEach(async () => {
    noteSyncServiceSpy = jasmine.createSpyObj('NoteSyncService', [
      'loadNotes',
      'searchNotes',
      'createNote',
      'updateNote',
      'deleteNote'
    ]);
    noteSyncServiceSpy.loadNotes.and.returnValue(of([]));
    noteSyncServiceSpy.searchNotes.and.returnValue(of([]));
    noteSyncServiceSpy.createNote.and.returnValue(of(makeNote()));
    noteSyncServiceSpy.updateNote.and.returnValue(of(makeNote()));
    noteSyncServiceSpy.deleteNote.and.returnValue(of(undefined as any));

    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
      .overrideComponent(NotesPanelComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .overrideProvider(NoteSyncService, { useValue: noteSyncServiceSpy })
      .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
      .compileComponents();

    fixture = TestBed.createComponent(NotesPanelComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ── Creation ──────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should not call loadNotes if factSheetId is null', () => {
    expect(noteSyncServiceSpy.loadNotes).not.toHaveBeenCalled();
  });

  // ── ngOnChanges / loadNotes ───────────────────────────────────────────────

  it('should call loadNotes when factSheetId changes to non-null', () => {
    const notes = [makeNote({ id: 1 }), makeNote({ id: 2, title: 'Second' })];
    noteSyncServiceSpy.loadNotes.and.returnValue(of(notes));

    component.factSheetId = 10;
    component.ngOnChanges({
      factSheetId: new SimpleChange(null, 10, true)
    });

    expect(noteSyncServiceSpy.loadNotes).toHaveBeenCalledWith(10);
    expect(component.notes.length).toBe(2);
    expect(component.filteredNotes.length).toBe(2);
    expect(component.isLoading).toBeFalse();
  });

  it('should reset selectedNote and isCreating on factSheetId change', () => {
    component.selectedNote = makeNote();
    component.isCreating = true;
    noteSyncServiceSpy.loadNotes.and.returnValue(of([]));

    component.factSheetId = 10;
    component.ngOnChanges({
      factSheetId: new SimpleChange(null, 10, true)
    });

    expect(component.selectedNote).toBeNull();
    expect(component.isCreating).toBeFalse();
  });

  it('should not call loadNotes when factSheetId is falsy', () => {
    component.factSheetId = null;
    component.ngOnChanges({
      factSheetId: new SimpleChange(10, null, false)
    });
    expect(noteSyncServiceSpy.loadNotes).not.toHaveBeenCalled();
  });

  it('should show snackbar on loadNotes failure', () => {
    noteSyncServiceSpy.loadNotes.and.returnValue(throwError(() => new Error('fail')));
    component.factSheetId = 10;
    component.ngOnChanges({ factSheetId: new SimpleChange(null, 10, true) });
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to load notes', 'Dismiss', { duration: 3000 });
    expect(component.isLoading).toBeFalse();
  });

  it('should not call loadNotes when factSheetId key is not in changes', () => {
    component.factSheetId = 10;
    component.ngOnChanges({ someOtherInput: new SimpleChange(null, 'x', true) });
    expect(noteSyncServiceSpy.loadNotes).not.toHaveBeenCalled();
  });

  // ── applyFilter ───────────────────────────────────────────────────────────

  it('should show all notes when filterType is ALL', () => {
    component.notes = [makeNote({ noteType: 'HUMAN' }), makeNote({ id: 2, noteType: 'AI' })];
    component.filterType = 'ALL';
    component.applyFilter();
    expect(component.filteredNotes.length).toBe(2);
  });

  it('should filter only HUMAN notes', () => {
    component.notes = [
      makeNote({ id: 1, noteType: 'HUMAN' }),
      makeNote({ id: 2, noteType: 'AI' }),
      makeNote({ id: 3, noteType: 'HUMAN' })
    ];
    component.filterType = 'HUMAN';
    component.applyFilter();
    expect(component.filteredNotes.length).toBe(2);
    expect(component.filteredNotes.every(n => n.noteType === 'HUMAN')).toBeTrue();
  });

  it('should filter only AI notes', () => {
    component.notes = [
      makeNote({ id: 1, noteType: 'HUMAN' }),
      makeNote({ id: 2, noteType: 'AI' })
    ];
    component.filterType = 'AI';
    component.applyFilter();
    expect(component.filteredNotes.length).toBe(1);
    expect(component.filteredNotes[0].noteType).toBe('AI');
  });

  // ── onFilterChange ────────────────────────────────────────────────────────

  it('should update filterType and apply filter', () => {
    component.notes = [makeNote({ noteType: 'HUMAN' }), makeNote({ id: 2, noteType: 'AI' })];
    component.onFilterChange('AI');
    expect(component.filterType).toBe('AI');
    expect(component.filteredNotes.length).toBe(1);
  });

  // ── selectNote ────────────────────────────────────────────────────────────

  it('should select note and populate edit fields', () => {
    const note = makeNote({ title: 'My Title', content: 'My Content', tags: 'a,b' });
    component.selectNote(note);
    expect(component.selectedNote).toBe(note);
    expect(component.editTitle).toBe('My Title');
    expect(component.editContent).toBe('My Content');
    expect(component.editTags).toBe('a,b');
    expect(component.isCreating).toBeFalse();
  });

  // ── deselectNote ──────────────────────────────────────────────────────────

  it('should deselect note', () => {
    component.selectedNote = makeNote();
    component.deselectNote();
    expect(component.selectedNote).toBeNull();
  });

  // ── startCreate / cancelCreate ────────────────────────────────────────────

  it('should set isCreating and clear fields on startCreate', () => {
    component.newTitle = 'old';
    component.newContent = 'old';
    component.startCreate();
    expect(component.isCreating).toBeTrue();
    expect(component.selectedNote).toBeNull();
    expect(component.newTitle).toBe('');
    expect(component.newContent).toBe('');
    expect(component.newTags).toBe('');
    expect(component.newNoteType).toBe('HUMAN');
  });

  it('should cancel creating', () => {
    component.isCreating = true;
    component.cancelCreate();
    expect(component.isCreating).toBeFalse();
  });

  // ── createNote ────────────────────────────────────────────────────────────

  it('should not create note when factSheetId is null', () => {
    component.factSheetId = null;
    component.newContent = 'content';
    component.createNote();
    expect(noteSyncServiceSpy.createNote).not.toHaveBeenCalled();
  });

  it('should not create note when newContent is empty', () => {
    component.factSheetId = 10;
    component.newContent = '   ';
    component.createNote();
    expect(noteSyncServiceSpy.createNote).not.toHaveBeenCalled();
  });

  it('should create note successfully', () => {
    const newNote = makeNote({ id: 99, title: 'Created', content: 'body' });
    noteSyncServiceSpy.createNote.and.returnValue(of(newNote));

    component.factSheetId = 10;
    component.newTitle = 'Created';
    component.newContent = 'body';
    component.newTags = 'tag1';
    component.newNoteType = 'HUMAN';
    component.createNote();

    expect(noteSyncServiceSpy.createNote).toHaveBeenCalledWith(10, {
      title: 'Created',
      content: 'body',
      noteType: 'HUMAN',
      tags: 'tag1'
    });
    expect(component.notes[0]).toBe(newNote);
    expect(component.isCreating).toBeFalse();
    expect(component.isSaving).toBeFalse();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Note created', 'OK', { duration: 2000 });
  });

  it('should pass undefined title when newTitle is empty', () => {
    component.factSheetId = 10;
    component.newTitle = '';
    component.newContent = 'body';
    component.newTags = '';
    component.createNote();

    const call = noteSyncServiceSpy.createNote.calls.mostRecent().args[1];
    expect(call.title).toBeUndefined();
    expect(call.tags).toBeUndefined();
  });

  it('should show snackbar on create failure', () => {
    noteSyncServiceSpy.createNote.and.returnValue(throwError(() => new Error('fail')));
    component.factSheetId = 10;
    component.newContent = 'body';
    component.createNote();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to create note', 'Dismiss', { duration: 3000 });
    expect(component.isSaving).toBeFalse();
  });

  // ── saveNote ──────────────────────────────────────────────────────────────

  it('should not save when no selectedNote', () => {
    component.selectedNote = null;
    component.saveNote();
    expect(noteSyncServiceSpy.updateNote).not.toHaveBeenCalled();
  });

  it('should save note and update notes array', () => {
    const original = makeNote({ id: 5, content: 'old' });
    const updated = makeNote({ id: 5, content: 'new' });
    noteSyncServiceSpy.updateNote.and.returnValue(of(updated));

    component.notes = [original];
    component.selectedNote = original;
    component.editTitle = 'Title';
    component.editContent = 'new';
    component.editTags = 'x';
    component.saveNote();

    expect(noteSyncServiceSpy.updateNote).toHaveBeenCalledWith(5, {
      title: 'Title',
      content: 'new',
      tags: 'x'
    });
    expect(component.notes[0]).toBe(updated);
    expect(component.selectedNote).toBe(updated);
    expect(snackBarSpy.open).toHaveBeenCalledWith('Note saved', 'OK', { duration: 2000 });
  });

  it('should show snackbar on save failure', () => {
    noteSyncServiceSpy.updateNote.and.returnValue(throwError(() => new Error('fail')));
    component.selectedNote = makeNote();
    component.saveNote();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to save note', 'Dismiss', { duration: 3000 });
    expect(component.isSaving).toBeFalse();
  });

  // ── deleteNote ────────────────────────────────────────────────────────────

  it('should delete note and remove from array', () => {
    const note = makeNote({ id: 3 });
    component.notes = [note, makeNote({ id: 4 })];
    component.applyFilter();
    component.deleteNote(note);

    expect(noteSyncServiceSpy.deleteNote).toHaveBeenCalledWith(3);
    expect(component.notes.some(n => n.id === 3)).toBeFalse();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Note deleted', 'OK', { duration: 2000 });
  });

  it('should deselect note if deleted note was selected', () => {
    const note = makeNote({ id: 3 });
    component.notes = [note];
    component.selectedNote = note;
    component.applyFilter();
    component.deleteNote(note);

    expect(component.selectedNote).toBeNull();
  });

  it('should show snackbar on delete failure', () => {
    noteSyncServiceSpy.deleteNote.and.returnValue(throwError(() => new Error('fail')));
    component.deleteNote(makeNote());
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to delete note', 'Dismiss', { duration: 3000 });
  });

  // ── onSearchChange with debounce ──────────────────────────────────────────

  it('should trigger search after debounce', fakeAsync(() => {
    const results = [makeNote({ id: 42, title: 'Found' })];
    noteSyncServiceSpy.searchNotes.and.returnValue(of(results));
    component.factSheetId = 10;

    component.onSearchChange('hello');
    tick(300);

    expect(noteSyncServiceSpy.searchNotes).toHaveBeenCalledWith(10, 'hello');
    expect(component.filteredNotes).toEqual(results);
  }));

  it('should reapply filter for empty search query after debounce', fakeAsync(() => {
    component.notes = [makeNote()];
    component.factSheetId = 10;

    component.onSearchChange('');
    tick(300);

    expect(noteSyncServiceSpy.searchNotes).not.toHaveBeenCalled();
    expect(component.filteredNotes.length).toBe(1);
  }));

  it('should show snackbar on search failure', fakeAsync(() => {
    noteSyncServiceSpy.searchNotes.and.returnValue(throwError(() => new Error('fail')));
    component.factSheetId = 10;

    component.onSearchChange('query');
    tick(300);

    expect(snackBarSpy.open).toHaveBeenCalledWith('Search failed', 'Dismiss', { duration: 3000 });
  }));

  // ── getTagList ────────────────────────────────────────────────────────────

  it('should return empty array for undefined tags', () => {
    expect(component.getTagList(undefined)).toEqual([]);
  });

  it('should return empty array for empty string', () => {
    expect(component.getTagList('')).toEqual([]);
  });

  it('should split and trim tags', () => {
    expect(component.getTagList('tag1, tag2 , tag3')).toEqual(['tag1', 'tag2', 'tag3']);
  });

  it('should filter out empty tags after trim', () => {
    expect(component.getTagList('tag1,,tag2')).toEqual(['tag1', 'tag2']);
  });

  // ── getSyncIcon ───────────────────────────────────────────────────────────

  it('should return cloud for NOTION sync provider', () => {
    expect(component.getSyncIcon(makeNote({ syncProvider: 'NOTION' }))).toBe('cloud');
  });

  it('should return folder for OBSIDIAN sync provider', () => {
    expect(component.getSyncIcon(makeNote({ syncProvider: 'OBSIDIAN' }))).toBe('folder');
  });

  it('should return null when no sync provider', () => {
    expect(component.getSyncIcon(makeNote({ syncProvider: undefined }))).toBeNull();
  });

  // ── formatDate ────────────────────────────────────────────────────────────

  it('should format date string as locale string', () => {
    const result = component.formatDate('2025-01-15T10:00:00Z');
    expect(typeof result).toBe('string');
    expect(result.length).toBeGreaterThan(0);
  });
});
