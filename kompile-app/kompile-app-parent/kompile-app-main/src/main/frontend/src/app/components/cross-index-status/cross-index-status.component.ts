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

import { Component, OnInit, OnDestroy, Input } from '@angular/core';
import { Subject, interval } from 'rxjs';
import { takeUntil, switchMap, filter } from 'rxjs/operators';
import { CrossIndexService } from '../../services/cross-index.service';
import {
  CrossIndexSummary,
  IndexedDocumentItem,
  IndexedDocumentDetail,
  IndexedPassageResponse,
  OverallIndexStatus,
  IndexStatus,
  SyncJobResponse,
  getCrossIndexStatusColor,
  getCrossIndexStatusIcon,
  getCrossIndexStatusLabel,
  getIndexStatusColor,
  getIndexStatusIcon
} from '../../models/api-models';

interface DocumentRow {
  id: number;
  sourceId: string;
  fileName: string | null;
  overallStatus: OverallIndexStatus;
  keywordIndexStatus: IndexStatus;
  vectorStoreStatus: IndexStatus;
  graphStatus: IndexStatus;
  keywordPassageCount: number;
  vectorPassageCount: number;
  graphNodeCount: number;
  updatedAt: string;
  expanded: boolean;
  passages?: IndexedPassageResponse;
  loadingPassages?: boolean;
}

interface SyncJob {
  jobId: string;
  status: string;
  progress: number;
  message: string;
}

@Component({
  selector: 'app-cross-index-status',
  standalone: false,
  templateUrl: './cross-index-status.component.html',
  styleUrls: ['./cross-index-status.component.css']
})
export class CrossIndexStatusComponent implements OnInit, OnDestroy {
  @Input() factSheetId: number = 1;

  // Summary data
  summary: CrossIndexSummary | null = null;
  loading = true;
  error: string | null = null;

  // Document list
  documents: DocumentRow[] = [];
  totalDocuments = 0;
  currentPage = 0;
  pageSize = 20;

  // Filters
  statusFilter: OverallIndexStatus | '' = '';
  searchQuery = '';

  // Sync state
  activeSyncJob: SyncJob | null = null;
  syncInProgress = false;

  // Selection
  selectedDocumentIds: Set<number> = new Set();
  allSelected = false;

  private destroy$ = new Subject<void>();

  // Helper functions exposed to template
  getCrossIndexStatusColor = getCrossIndexStatusColor;
  getCrossIndexStatusIcon = getCrossIndexStatusIcon;
  getCrossIndexStatusLabel = getCrossIndexStatusLabel;
  getIndexStatusColor = getIndexStatusColor;
  getIndexStatusIcon = getIndexStatusIcon;

  constructor(private crossIndexService: CrossIndexService) {}

  ngOnInit(): void {
    this.loadSummary();
    this.loadDocuments();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DATA LOADING
  // ═══════════════════════════════════════════════════════════════════════════

  loadSummary(): void {
    this.crossIndexService.getCrossIndexSummaryForFactSheet(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (summary) => {
          this.summary = summary;
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load cross-index summary';
          this.loading = false;
          console.error('Error loading summary:', err);
        }
      });
  }

  loadDocuments(): void {
    const offset = this.currentPage * this.pageSize;
    const status = this.statusFilter || undefined;
    const search = this.searchQuery || undefined;

    this.crossIndexService.getDocuments(this.factSheetId, offset, this.pageSize, status, search)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          // Backend returns a Spring Page: {content: [...], totalElements: N}.
          // The IndexedDocumentResponse type expects {documents: [...], total: N}.
          // Guard both shapes so the component never crashes on an unexpected response.
          const raw = response as any;
          const items: IndexedDocumentItem[] = raw?.documents ?? raw?.content ?? [];
          this.documents = items.map((doc: IndexedDocumentItem) => ({
            ...doc,
            expanded: false,
            passages: undefined,
            loadingPassages: false
          }));
          this.totalDocuments = response?.total ?? raw?.totalElements ?? items.length;
          this.updateSelectionState();
        },
        error: (err) => {
          console.error('Error loading documents:', err);
        }
      });
  }

  loadPassages(doc: DocumentRow): void {
    if (doc.passages) {
      return; // Already loaded
    }

    doc.loadingPassages = true;
    this.crossIndexService.getPassages(doc.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          doc.passages = response;
          doc.loadingPassages = false;
        },
        error: (err) => {
          doc.loadingPassages = false;
          console.error('Error loading passages:', err);
        }
      });
  }

  refresh(): void {
    this.loading = true;
    this.loadSummary();
    this.loadDocuments();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // FILTERING & PAGINATION
  // ═══════════════════════════════════════════════════════════════════════════

  onStatusFilterChange(): void {
    this.currentPage = 0;
    this.loadDocuments();
  }

  onSearch(): void {
    this.currentPage = 0;
    this.loadDocuments();
  }

  clearFilters(): void {
    this.statusFilter = '';
    this.searchQuery = '';
    this.currentPage = 0;
    this.loadDocuments();
  }

  nextPage(): void {
    if ((this.currentPage + 1) * this.pageSize < this.totalDocuments) {
      this.currentPage++;
      this.loadDocuments();
    }
  }

  prevPage(): void {
    if (this.currentPage > 0) {
      this.currentPage--;
      this.loadDocuments();
    }
  }

  get pageStart(): number {
    return this.currentPage * this.pageSize + 1;
  }

  get pageEnd(): number {
    return Math.min((this.currentPage + 1) * this.pageSize, this.totalDocuments);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ROW EXPANSION
  // ═══════════════════════════════════════════════════════════════════════════

  // Predicate for the expandedDetail row def — always returns true so the row
  // is present in the DOM for every data row; the [@detailExpand] animation
  // drives visible expansion, not row existence.
  isExpanded = (_index: number, _doc: DocumentRow) => true;

  toggleExpand(doc: DocumentRow): void {
    doc.expanded = !doc.expanded;
    if (doc.expanded && !doc.passages) {
      this.loadPassages(doc);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SELECTION
  // ═══════════════════════════════════════════════════════════════════════════

  toggleSelectAll(): void {
    if (this.allSelected) {
      this.selectedDocumentIds.clear();
    } else {
      this.documents.forEach(doc => this.selectedDocumentIds.add(doc.id));
    }
    this.updateSelectionState();
  }

  toggleSelectDocument(doc: DocumentRow): void {
    if (this.selectedDocumentIds.has(doc.id)) {
      this.selectedDocumentIds.delete(doc.id);
    } else {
      this.selectedDocumentIds.add(doc.id);
    }
    this.updateSelectionState();
  }

  isSelected(doc: DocumentRow): boolean {
    return this.selectedDocumentIds.has(doc.id);
  }

  private updateSelectionState(): void {
    this.allSelected = this.documents.length > 0 &&
      this.documents.every(doc => this.selectedDocumentIds.has(doc.id));
  }

  get hasSelection(): boolean {
    return this.selectedDocumentIds.size > 0;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SYNC OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  syncToVectorStore(): void {
    this.startSync(() => this.crossIndexService.syncToVectorStore(this.factSheetId));
  }

  syncToKnowledgeGraph(): void {
    this.startSync(() => this.crossIndexService.syncToKnowledgeGraph(this.factSheetId));
  }

  syncAll(): void {
    this.startSync(() => this.crossIndexService.syncAll(this.factSheetId));
  }

  syncSelectedToVector(): void {
    if (!this.hasSelection) return;
    const ids = Array.from(this.selectedDocumentIds);
    this.startSync(() => this.crossIndexService.syncDocuments(ids, ['VECTOR_STORE']));
  }

  syncSelectedToGraph(): void {
    if (!this.hasSelection) return;
    const ids = Array.from(this.selectedDocumentIds);
    this.startSync(() => this.crossIndexService.syncDocuments(ids, ['KNOWLEDGE_GRAPH']));
  }

  syncSelectedToAll(): void {
    if (!this.hasSelection) return;
    const ids = Array.from(this.selectedDocumentIds);
    this.startSync(() => this.crossIndexService.syncDocuments(ids, ['VECTOR_STORE', 'KNOWLEDGE_GRAPH']));
  }

  private startSync(syncFn: () => ReturnType<typeof this.crossIndexService.syncAll>): void {
    this.syncInProgress = true;
    this.activeSyncJob = {
      jobId: '',
      status: 'STARTING',
      progress: 0,
      message: 'Starting sync...'
    };

    syncFn()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          const jobId = response.jobId || '';
          this.activeSyncJob = {
            jobId: jobId,
            status: response.status,
            progress: 0,
            message: response.message
          };
          if (jobId) {
            this.pollSyncStatus(jobId);
          }
        },
        error: (err) => {
          this.syncInProgress = false;
          this.activeSyncJob = null;
          console.error('Error starting sync:', err);
        }
      });
  }

  private pollSyncStatus(jobId: string): void {
    interval(1000)
      .pipe(
        takeUntil(this.destroy$),
        switchMap(() => this.crossIndexService.getSyncJobStatus(jobId)),
        filter(response => response !== null)
      )
      .subscribe({
        next: (response) => {
          if (this.activeSyncJob) {
            this.activeSyncJob.status = response.status;
            this.activeSyncJob.progress = response.progress;
            this.activeSyncJob.message = response.message;
          }

          if (response.status === 'COMPLETED' || response.status === 'FAILED' || response.status === 'CANCELLED') {
            this.syncInProgress = false;
            setTimeout(() => {
              this.activeSyncJob = null;
              this.refresh();
            }, 2000);
          }
        },
        error: (err) => {
          console.error('Error polling sync status:', err);
          this.syncInProgress = false;
          this.activeSyncJob = null;
        }
      });
  }

  cancelSync(): void {
    if (this.activeSyncJob) {
      this.crossIndexService.cancelSyncJob(this.activeSyncJob.jobId)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            if (this.activeSyncJob) {
              this.activeSyncJob.status = 'CANCELLED';
              this.activeSyncJob.message = 'Sync cancelled';
            }
          },
          error: (err) => {
            console.error('Error cancelling sync:', err);
          }
        });
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // UTILITY
  // ═══════════════════════════════════════════════════════════════════════════

  formatDate(dateString: string): string {
    if (!dateString) return 'Never';
    return new Date(dateString).toLocaleString();
  }

  truncateSourceId(sourceId: string): string {
    if (!sourceId) return '';
    if (sourceId.length <= 50) return sourceId;
    return '...' + sourceId.slice(-47);
  }
}
