/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, Input, OnChanges, OnDestroy, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatMenuModule } from '@angular/material/menu';
import { MatCardModule } from '@angular/material/card';
import { Subject } from 'rxjs';
import { takeUntil, debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { NoteSyncService } from '../../services/note-sync.service';
import { NoteModel } from '../../models/sync-models';

@Component({
  selector: 'app-notes-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatChipsModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatDividerModule,
    MatMenuModule,
    MatCardModule
  ],
  templateUrl: './notes-panel.component.html',
  styleUrls: ['./notes-panel.component.css']
})
export class NotesPanelComponent implements OnChanges, OnDestroy {
  @Input() factSheetId: number | null | undefined = null;

  notes: NoteModel[] = [];
  filteredNotes: NoteModel[] = [];
  selectedNote: NoteModel | null = null;
  isLoading = false;
  isSaving = false;

  // New note form
  isCreating = false;
  newTitle = '';
  newContent = '';
  newTags = '';
  newNoteType: 'HUMAN' | 'AI' = 'HUMAN';

  // Edit state
  editTitle = '';
  editContent = '';
  editTags = '';

  // Search
  searchQuery = '';
  private searchSubject = new Subject<string>();

  // Filter
  filterType: 'ALL' | 'HUMAN' | 'AI' = 'ALL';

  private destroy$ = new Subject<void>();

  constructor(
    private noteSyncService: NoteSyncService,
    private snackBar: MatSnackBar
  ) {
    this.searchSubject.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      takeUntil(this.destroy$)
    ).subscribe(query => {
      if (query.trim()) {
        this.performSearch(query);
      } else {
        this.applyFilter();
      }
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && this.factSheetId) {
      this.loadNotes();
      this.selectedNote = null;
      this.isCreating = false;
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadNotes(): void {
    if (!this.factSheetId) return;
    this.isLoading = true;
    this.noteSyncService.loadNotes(this.factSheetId).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: notes => {
        this.notes = notes;
        this.applyFilter();
        this.isLoading = false;
      },
      error: err => {
        this.snackBar.open('Failed to load notes', 'Dismiss', { duration: 3000 });
        this.isLoading = false;
      }
    });
  }

  onSearchChange(query: string): void {
    this.searchSubject.next(query);
  }

  private performSearch(query: string): void {
    if (!this.factSheetId) return;
    this.isLoading = true;
    this.noteSyncService.searchNotes(this.factSheetId, query).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: notes => {
        this.filteredNotes = notes;
        this.isLoading = false;
      },
      error: () => {
        this.snackBar.open('Search failed', 'Dismiss', { duration: 3000 });
        this.isLoading = false;
      }
    });
  }

  applyFilter(): void {
    if (this.filterType === 'ALL') {
      this.filteredNotes = [...this.notes];
    } else {
      this.filteredNotes = this.notes.filter(n => n.noteType === this.filterType);
    }
  }

  onFilterChange(filter: 'ALL' | 'HUMAN' | 'AI'): void {
    this.filterType = filter;
    this.applyFilter();
  }

  selectNote(note: NoteModel): void {
    this.selectedNote = note;
    this.editTitle = note.title || '';
    this.editContent = note.content;
    this.editTags = note.tags || '';
    this.isCreating = false;
  }

  startCreate(): void {
    this.isCreating = true;
    this.selectedNote = null;
    this.newTitle = '';
    this.newContent = '';
    this.newTags = '';
    this.newNoteType = 'HUMAN';
  }

  cancelCreate(): void {
    this.isCreating = false;
  }

  createNote(): void {
    if (!this.factSheetId || !this.newContent.trim()) return;
    this.isSaving = true;
    this.noteSyncService.createNote(this.factSheetId, {
      title: this.newTitle || undefined,
      content: this.newContent,
      noteType: this.newNoteType,
      tags: this.newTags || undefined
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: note => {
        this.notes.unshift(note);
        this.applyFilter();
        this.isCreating = false;
        this.isSaving = false;
        this.snackBar.open('Note created', 'OK', { duration: 2000 });
      },
      error: () => {
        this.snackBar.open('Failed to create note', 'Dismiss', { duration: 3000 });
        this.isSaving = false;
      }
    });
  }

  saveNote(): void {
    if (!this.selectedNote) return;
    this.isSaving = true;
    this.noteSyncService.updateNote(this.selectedNote.id, {
      title: this.editTitle || undefined,
      content: this.editContent,
      tags: this.editTags || undefined
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: updated => {
        const idx = this.notes.findIndex(n => n.id === updated.id);
        if (idx >= 0) this.notes[idx] = updated;
        this.selectedNote = updated;
        this.applyFilter();
        this.isSaving = false;
        this.snackBar.open('Note saved', 'OK', { duration: 2000 });
      },
      error: () => {
        this.snackBar.open('Failed to save note', 'Dismiss', { duration: 3000 });
        this.isSaving = false;
      }
    });
  }

  deleteNote(note: NoteModel): void {
    this.noteSyncService.deleteNote(note.id).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: () => {
        this.notes = this.notes.filter(n => n.id !== note.id);
        this.applyFilter();
        if (this.selectedNote?.id === note.id) {
          this.selectedNote = null;
        }
        this.snackBar.open('Note deleted', 'OK', { duration: 2000 });
      },
      error: () => {
        this.snackBar.open('Failed to delete note', 'Dismiss', { duration: 3000 });
      }
    });
  }

  deselectNote(): void {
    this.selectedNote = null;
  }

  getTagList(tags: string | undefined): string[] {
    if (!tags) return [];
    return tags.split(',').map(t => t.trim()).filter(t => t.length > 0);
  }

  formatDate(dateStr: string): string {
    return new Date(dateStr).toLocaleString();
  }

  getSyncIcon(note: NoteModel): string | null {
    if (note.syncProvider === 'NOTION') return 'cloud';
    if (note.syncProvider === 'OBSIDIAN') return 'folder';
    return null;
  }
}
