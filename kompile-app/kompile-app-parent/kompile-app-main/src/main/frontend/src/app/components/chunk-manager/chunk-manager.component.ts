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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import { Component, OnInit, OnDestroy, ViewChild, ChangeDetectorRef } from '@angular/core';
import { MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatTableDataSource } from '@angular/material/table';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';
import { SelectionModel } from '@angular/cdk/collections';
import { Subject } from 'rxjs';
import { takeUntil, filter, switchMap } from 'rxjs/operators';
import { ChunkManagerService } from '../../services/chunk-manager.service';
import {
  ChunkSummary,
  ChunkDetail,
  SourceInfo,
  DeduplicationStrategy,
  KeepPolicy,
  DuplicateAnalysisResponse,
  IndexStats,
  ChunkEditDetail,
  ChunkUpdateRequest,
  ChunkEntityDto,
  SEMANTIC_TYPES,
  ENTITY_TYPES
} from '../../models/chunk-manager.models';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';

@Component({
  standalone: false,
  selector: 'app-chunk-manager',
  templateUrl: './chunk-manager.component.html',
  styleUrls: ['./chunk-manager.component.css']
})
export class ChunkManagerComponent implements OnInit, OnDestroy {

  private destroy$ = new Subject<void>();

  // Loading states
  isLoading = false;
  isLoadingDetail = false;
  isExporting = false;
  isDeduplicating = false;

  // Chunk list
  chunks: ChunkSummary[] = [];
  dataSource = new MatTableDataSource<ChunkSummary>([]);
  displayedColumns: string[] = ['select', 'id', 'preview', 'source', 'chunkIndex', 'location', 'actions'];
  selection = new SelectionModel<ChunkSummary>(true, []);

  // Index statistics
  indexStats: IndexStats | null = null;

  // Pagination
  totalCount = 0;
  pageSize = 20;
  pageIndex = 0;
  pageSizeOptions = [10, 20, 50, 100];

  // Filters
  sources: SourceInfo[] = [];
  selectedSourceId: string = '';

  // Detail view
  selectedChunk: ChunkDetail | null = null;
  showRenderedMarkdown = false;

  // Deduplication
  showDedupDialog = false;
  dedupStrategy: DeduplicationStrategy = 'content_hash';
  dedupKeepPolicy: KeepPolicy = 'first';
  dedupDryRun = true;
  duplicateAnalysis: DuplicateAnalysisResponse | null = null;

  // Clear all
  clearToken: string | null = null;
  clearTokenExpiry: number = 0;

  // Edit mode
  isEditMode = false;
  isSaving = false;
  editChunk: ChunkEditDetail | null = null;
  editContent: string = '';
  editSemanticType: string = '';
  editSourceTitle: string = '';
  editSourceAuthor: string = '';
  editSourceDate: string = '';
  editSourceUrl: string = '';
  editEntities: ChunkEntityDto[] = [];
  newEntityName: string = '';
  newEntityType: string = 'CONCEPT';

  // Available types
  semanticTypes = SEMANTIC_TYPES;
  entityTypes = ENTITY_TYPES;

  @ViewChild(MatPaginator) paginator!: MatPaginator;

  constructor(
    private chunkManagerService: ChunkManagerService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadChunks();
    this.loadSources();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DATA LOADING
  // ═══════════════════════════════════════════════════════════════════════════

  loadChunks(): void {
    this.isLoading = true;
    const offset = this.pageIndex * this.pageSize;

    this.chunkManagerService.listChunks(offset, this.pageSize, this.selectedSourceId || undefined)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.chunks = response.chunks;
          this.dataSource.data = response.chunks;
          this.totalCount = response.totalCount;
          // Store index statistics
          if (response.indexStats) {
            this.indexStats = response.indexStats;
          }
          this.isLoading = false;
          this.cdr.detectChanges();
        },
        error: (error) => {
          console.error('Error loading chunks:', error);
          this.showError('Failed to load chunks');
          this.isLoading = false;
          this.cdr.detectChanges();
        }
      });
  }

  loadSources(): void {
    this.chunkManagerService.listSources()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.sources = response.sources;
          this.cdr.detectChanges();
        },
        error: (error) => {
          console.error('Error loading sources:', error);
          this.cdr.detectChanges();
        }
      });
  }

  onPageChange(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadChunks();
  }

  onSourceFilterChange(): void {
    this.pageIndex = 0;
    this.loadChunks();
  }

  refresh(): void {
    this.selection.clear();
    this.loadChunks();
    this.loadSources();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SELECTION
  // ═══════════════════════════════════════════════════════════════════════════

  isAllSelected(): boolean {
    const numSelected = this.selection.selected.length;
    const numRows = this.dataSource.data.length;
    return numSelected === numRows;
  }

  masterToggle(): void {
    if (this.isAllSelected()) {
      this.selection.clear();
    } else {
      this.dataSource.data.forEach(row => this.selection.select(row));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CHUNK DETAIL
  // ═══════════════════════════════════════════════════════════════════════════

  viewChunk(chunk: ChunkSummary): void {
    this.isLoadingDetail = true;
    this.chunkManagerService.getChunk(chunk.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (detail) => {
          this.selectedChunk = detail;
          this.isLoadingDetail = false;
        },
        error: (error) => {
          console.error('Error loading chunk detail:', error);
          this.showError('Failed to load chunk details');
          this.isLoadingDetail = false;
        }
      });
  }

  closeDetail(): void {
    this.selectedChunk = null;
    this.isEditMode = false;
    this.editChunk = null;
  }

  toggleMarkdownView(): void {
    this.showRenderedMarkdown = !this.showRenderedMarkdown;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // EDIT MODE
  // ═══════════════════════════════════════════════════════════════════════════

  enterEditMode(): void {
    if (!this.selectedChunk) return;

    this.isLoadingDetail = true;
    this.chunkManagerService.getChunkForEdit(this.selectedChunk.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (detail) => {
          this.editChunk = detail;
          this.editContent = detail.content || '';
          this.editSemanticType = detail.semanticType || 'TEXT';
          this.editSourceTitle = detail.sourceTitle || '';
          this.editSourceAuthor = detail.sourceAuthor || '';
          this.editSourceDate = detail.sourceDate || '';
          this.editSourceUrl = detail.sourceUrl || '';
          this.editEntities = detail.entities ? [...detail.entities] : [];
          this.isEditMode = true;
          this.isLoadingDetail = false;
        },
        error: (error) => {
          console.error('Error loading chunk for edit:', error);
          this.showError('Failed to load chunk for editing');
          this.isLoadingDetail = false;
        }
      });
  }

  cancelEdit(): void {
    this.isEditMode = false;
    this.editChunk = null;
    this.editEntities = [];
  }

  saveChunk(): void {
    if (!this.editChunk) return;

    this.isSaving = true;
    const request: ChunkUpdateRequest = {
      content: this.editContent,
      semanticType: this.editSemanticType,
      sourceTitle: this.editSourceTitle || undefined,
      sourceAuthor: this.editSourceAuthor || undefined,
      sourceDate: this.editSourceDate || undefined,
      sourceUrl: this.editSourceUrl || undefined,
      entities: this.editEntities.length > 0 ? this.editEntities : undefined
    };

    this.chunkManagerService.updateChunk(this.editChunk.id, request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.isSaving = false;
          if (response.success) {
            this.showSuccess('Chunk updated successfully');
            // Update the selected chunk with new data
            if (response.updatedChunk) {
              this.selectedChunk = {
                ...this.selectedChunk!,
                content: response.updatedChunk.content,
                contentLength: response.updatedChunk.contentLength,
                metadata: response.updatedChunk.metadata
              };
            }
            this.isEditMode = false;
            this.editChunk = null;
            // Refresh the list to show updated preview
            this.loadChunks();
          } else {
            this.showError(response.message);
          }
        },
        error: (error) => {
          console.error('Error saving chunk:', error);
          this.showError('Failed to save chunk');
          this.isSaving = false;
        }
      });
  }

  // Entity management
  addEntity(): void {
    if (!this.newEntityName.trim()) return;

    this.editEntities.push({
      name: this.newEntityName.trim(),
      type: this.newEntityType
    });
    this.newEntityName = '';
  }

  removeEntity(index: number): void {
    this.editEntities.splice(index, 1);
  }

  getSemanticTypeLabel(type: string): string {
    return type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, l => l.toUpperCase());
  }

  getEntityTypeLabel(type: string): string {
    return type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, l => l.toUpperCase());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DELETE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  deleteChunk(chunk: ChunkSummary): void {
    const dialogData: ConfirmDialogData = {
      title: 'Delete Chunk',
      message: `Are you sure you want to delete chunk "${chunk.id.substring(0, 20)}..."?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        switchMap(() => this.chunkManagerService.deleteChunk(chunk.id)),
        takeUntil(this.destroy$)
      )
      .subscribe({
        next: (response) => {
          if (response.success) {
            this.showSuccess('Chunk deleted');
            this.refresh();
          } else {
            this.showError(response.message);
          }
          this.cdr.detectChanges();
        },
        error: (error) => {
          console.error('Error deleting chunk:', error);
          this.showError('Failed to delete chunk');
          this.cdr.detectChanges();
        }
      });
  }

  deleteSelected(): void {
    const selectedIds = this.selection.selected.map(c => c.id);
    if (selectedIds.length === 0) {
      this.showError('No chunks selected');
      return;
    }

    const dialogData: ConfirmDialogData = {
      title: 'Delete Selected Chunks',
      message: `Are you sure you want to delete ${selectedIds.length} selected chunk(s)? This action cannot be undone.`,
      confirmText: 'Delete All',
      confirmColor: 'warn',
      icon: 'delete_sweep'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        switchMap(() => this.chunkManagerService.deleteChunks(selectedIds)),
        takeUntil(this.destroy$)
      )
      .subscribe({
        next: (response) => {
          if (response.success) {
            this.showSuccess(`Deleted ${response.affectedCount} chunks`);
            this.selection.clear();
            this.refresh();
          } else {
            this.showError(response.message);
          }
          this.cdr.detectChanges();
        },
        error: (error) => {
          console.error('Error deleting chunks:', error);
          this.showError('Failed to delete chunks');
          this.cdr.detectChanges();
        }
      });
  }

  deleteBySource(sourceId: string): void {
    const source = this.sources.find(s => s.sourceId === sourceId);
    const filename = source?.filename || sourceId;

    const dialogData: ConfirmDialogData = {
      title: 'Delete by Source',
      message: `Are you sure you want to delete all chunks from "${filename}"? This action cannot be undone.`,
      confirmText: 'Delete All',
      confirmColor: 'warn',
      icon: 'folder_delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        switchMap(() => this.chunkManagerService.deleteBySource(sourceId)),
        takeUntil(this.destroy$)
      )
      .subscribe({
        next: (response) => {
          if (response.success) {
            this.showSuccess(response.message);
            this.refresh();
          } else {
            this.showError(response.message);
          }
          this.cdr.detectChanges();
        },
        error: (error) => {
          console.error('Error deleting by source:', error);
          this.showError('Failed to delete chunks');
          this.cdr.detectChanges();
        }
      });
  }

  requestClearToken(): void {
    this.chunkManagerService.generateClearToken()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.clearToken = response.token;
          this.clearTokenExpiry = Date.now() + (response.expiresIn * 1000);
          this.showSuccess(`Token generated. Valid for ${response.expiresIn} seconds.`);
        },
        error: (error) => {
          console.error('Error generating clear token:', error);
          this.showError('Failed to generate confirmation token');
        }
      });
  }

  clearAll(): void {
    if (!this.clearToken) {
      this.showError('Please generate a confirmation token first');
      return;
    }

    if (Date.now() > this.clearTokenExpiry) {
      this.clearToken = null;
      this.showError('Confirmation token expired. Please generate a new one.');
      return;
    }

    const dialogData: ConfirmDialogData = {
      title: 'Clear All Chunks',
      message: 'This will permanently delete ALL chunks from the vector store. This action cannot be undone. Are you absolutely sure?',
      confirmText: 'Clear Everything',
      confirmColor: 'warn',
      icon: 'warning'
    };

    const token = this.clearToken;
    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        switchMap(() => this.chunkManagerService.clearAll(token)),
        takeUntil(this.destroy$)
      )
      .subscribe({
        next: (response) => {
          this.clearToken = null;
          if (response.success) {
            this.showSuccess(response.message);
            this.refresh();
          } else {
            this.showError(response.message);
          }
          this.cdr.detectChanges();
        },
        error: (error) => {
          console.error('Error clearing all:', error);
          this.showError('Failed to clear vector store');
          this.clearToken = null;
          this.cdr.detectChanges();
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DEDUPLICATION
  // ═══════════════════════════════════════════════════════════════════════════

  openDedupDialog(): void {
    this.showDedupDialog = true;
    this.duplicateAnalysis = null;
    this.dedupDryRun = true;
  }

  closeDedupDialog(): void {
    this.showDedupDialog = false;
    this.duplicateAnalysis = null;
  }

  analyzeDuplicates(): void {
    this.isDeduplicating = true;
    this.chunkManagerService.analyzeDuplicates(this.dedupStrategy)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.duplicateAnalysis = response;
          this.isDeduplicating = false;
          this.cdr.detectChanges();
        },
        error: (error) => {
          console.error('Error analyzing duplicates:', error);
          this.showError('Failed to analyze duplicates');
          this.isDeduplicating = false;
          this.cdr.detectChanges();
        }
      });
  }

  runDeduplication(): void {
    this.isDeduplicating = true;
    this.chunkManagerService.deduplicate({
      strategy: this.dedupStrategy,
      keepPolicy: this.dedupKeepPolicy,
      dryRun: this.dedupDryRun
    })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.isDeduplicating = false;
          if (result.success) {
            this.showSuccess(result.message);
            if (!this.dedupDryRun) {
              this.refresh();
              this.closeDedupDialog();
            }
          } else {
            this.showError(result.message);
          }
          this.cdr.detectChanges();
        },
        error: (error) => {
          console.error('Error during deduplication:', error);
          this.showError('Failed to run deduplication');
          this.isDeduplicating = false;
          this.cdr.detectChanges();
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // EXPORT
  // ═══════════════════════════════════════════════════════════════════════════

  exportSelected(): void {
    const selectedIds = this.selection.selected.map(c => c.id);
    this.exportChunks(selectedIds, undefined);
  }

  exportBySource(sourceId: string): void {
    this.exportChunks(undefined, sourceId);
  }

  exportAll(): void {
    this.exportChunks(undefined, undefined);
  }

  private exportChunks(chunkIds?: string[], sourceId?: string): void {
    this.isExporting = true;
    this.chunkManagerService.downloadExport({
      chunkIds: chunkIds,
      sourceId: sourceId,
      includeMetadata: true,
      format: 'markdown'
    })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (blob) => {
          const filename = `chunks_export_${Date.now()}.md`;
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = filename;
          a.click();
          window.URL.revokeObjectURL(url);
          this.isExporting = false;
          this.showSuccess('Export downloaded');
        },
        error: (error) => {
          console.error('Error exporting:', error);
          this.showError('Failed to export chunks');
          this.isExporting = false;
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // UTILITY
  // ═══════════════════════════════════════════════════════════════════════════

  private showSuccess(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 3000,
      panelClass: ['success-snackbar']
    });
  }

  private showError(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 5000,
      panelClass: ['error-snackbar']
    });
  }

  getSourceDisplay(sourceId: string | undefined): string {
    if (!sourceId) return 'Unknown';
    const lastSlash = Math.max(sourceId.lastIndexOf('/'), sourceId.lastIndexOf('\\'));
    if (lastSlash >= 0 && lastSlash < sourceId.length - 1) {
      return sourceId.substring(lastSlash + 1);
    }
    return sourceId.length > 30 ? sourceId.substring(0, 27) + '...' : sourceId;
  }

  copyToClipboard(text: string): void {
    navigator.clipboard.writeText(text).then(() => {
      this.showSuccess('Copied to clipboard');
    });
  }
}
