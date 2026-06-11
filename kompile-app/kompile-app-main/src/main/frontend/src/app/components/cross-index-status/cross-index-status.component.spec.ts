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

import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
  discardPeriodicTasks
} from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { CrossIndexStatusComponent } from './cross-index-status.component';
import { CrossIndexService } from '../../services/cross-index.service';
import {
  CrossIndexSummary,
  IndexedDocumentResponse,
  IndexedDocumentItem,
  IndexedPassageResponse,
  SyncJobResponse,
  SyncJobStatusResponse,
  OverallIndexStatus,
  IndexStatus
} from '../../models/api-models';

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

function makeSummary(overrides: Partial<CrossIndexSummary> = {}): CrossIndexSummary {
  return {
    totalDocuments: 10,
    fullyIndexedDocuments: 8,
    partiallyIndexedDocuments: 1,
    notIndexedDocuments: 1,
    outOfSyncDocuments: 0,
    ...overrides
  } as CrossIndexSummary;
}

function makeDocumentItem(id: number, status: string = 'FULLY_INDEXED'): IndexedDocumentItem {
  return {
    id,
    sourceId: `src-${id}`,
    fileName: `file-${id}.pdf`,
    overallStatus: status as OverallIndexStatus,
    keywordIndexStatus: 'INDEXED' as IndexStatus,
    vectorStoreStatus: 'INDEXED' as IndexStatus,
    graphStatus: 'NOT_INDEXED' as IndexStatus,
    keywordPassageCount: 5,
    vectorPassageCount: 5,
    graphNodeCount: 0,
    updatedAt: '2025-01-01T00:00:00Z'
  } as IndexedDocumentItem;
}

function makeDocumentResponse(count = 3): IndexedDocumentResponse {
  return {
    documents: Array.from({ length: count }, (_, i) => makeDocumentItem(i + 1)),
    total: count
  } as IndexedDocumentResponse;
}

function makeSyncJobResponse(jobId = 'job-1'): SyncJobResponse {
  return { jobId, status: 'STARTED', message: 'Sync started' } as SyncJobResponse;
}

describe('CrossIndexStatusComponent', () => {
  let component: CrossIndexStatusComponent;
  let fixture: ComponentFixture<CrossIndexStatusComponent>;
  let crossIndexServiceSpy: jasmine.SpyObj<CrossIndexService>;

  beforeEach(async () => {
    crossIndexServiceSpy = jasmine.createSpyObj('CrossIndexService', [
      'getCrossIndexSummaryForFactSheet',
      'getDocuments',
      'getPassages',
      'syncToVectorStore',
      'syncToKnowledgeGraph',
      'syncAll',
      'syncDocuments',
      'getSyncJobStatus',
      'cancelSyncJob'
    ]);

    // Default returns
    crossIndexServiceSpy.getCrossIndexSummaryForFactSheet.and.returnValue(of(makeSummary()));
    crossIndexServiceSpy.getDocuments.and.returnValue(of(makeDocumentResponse()));
    crossIndexServiceSpy.getSyncJobStatus.and.returnValue(of({
      jobId: 'job-1', status: 'COMPLETED', progress: 100, message: 'Done'
    } as SyncJobStatusResponse));

    await TestBed.configureTestingModule({
      declarations: [CrossIndexStatusComponent],
      imports: [NoopAnimationsModule],
      providers: [
        { provide: CrossIndexService, useValue: crossIndexServiceSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideTemplate(CrossIndexStatusComponent, '<div></div>')
    .compileComponents();

    fixture = TestBed.createComponent(CrossIndexStatusComponent);
    component = fixture.componentInstance;
    component.factSheetId = 1;
  });

  afterEach(() => {
    component.ngOnDestroy();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // Creation
  // ─────────────────────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should have factSheetId defaulting to 1', () => {
    expect(component.factSheetId).toBe(1);
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // ngOnInit
  // ─────────────────────────────────────────────────────────────────────────────

  describe('ngOnInit()', () => {
    it('should load summary on init', () => {
      fixture.detectChanges();
      expect(crossIndexServiceSpy.getCrossIndexSummaryForFactSheet).toHaveBeenCalledWith(1);
    });

    it('should load documents on init', () => {
      fixture.detectChanges();
      expect(crossIndexServiceSpy.getDocuments).toHaveBeenCalled();
    });

    it('should populate summary after load', () => {
      const summary = makeSummary({ totalDocuments: 50, fullyIndexedDocuments: 40 });
      crossIndexServiceSpy.getCrossIndexSummaryForFactSheet.and.returnValue(of(summary));

      fixture.detectChanges();

      expect(component.summary?.totalDocuments).toBe(50);
      expect(component.loading).toBeFalse();
    });

    it('should populate documents after load', () => {
      crossIndexServiceSpy.getDocuments.and.returnValue(of(makeDocumentResponse(5)));

      fixture.detectChanges();

      expect(component.documents.length).toBe(5);
      expect(component.totalDocuments).toBe(5);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // loadSummary()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadSummary()', () => {
    it('should set error on failure', () => {
      crossIndexServiceSpy.getCrossIndexSummaryForFactSheet.and.returnValue(
        throwError(() => new Error('Network error'))
      );

      component.loadSummary();

      expect(component.error).toBe('Failed to load cross-index summary');
      expect(component.loading).toBeFalse();
    });

    it('should set loading=false on successful load', () => {
      component.loading = true;
      crossIndexServiceSpy.getCrossIndexSummaryForFactSheet.and.returnValue(of(makeSummary()));

      component.loadSummary();

      expect(component.loading).toBeFalse();
      expect(component.summary).toBeTruthy();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // loadDocuments()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadDocuments()', () => {
    it('should call getDocuments with factSheetId and pagination', () => {
      component.currentPage = 0;
      component.pageSize = 20;

      component.loadDocuments();

      expect(crossIndexServiceSpy.getDocuments).toHaveBeenCalledWith(
        1, 0, 20, undefined, undefined
      );
    });

    it('should pass status filter when set', () => {
      component.statusFilter = 'FULLY_INDEXED' as OverallIndexStatus;
      component.loadDocuments();
      expect(crossIndexServiceSpy.getDocuments).toHaveBeenCalledWith(
        1, jasmine.any(Number), jasmine.any(Number), 'FULLY_INDEXED', undefined
      );
    });

    it('should pass search query when set', () => {
      component.searchQuery = 'invoice';
      component.loadDocuments();
      expect(crossIndexServiceSpy.getDocuments).toHaveBeenCalledWith(
        1, jasmine.any(Number), jasmine.any(Number), undefined, 'invoice'
      );
    });

    it('should map documents to DocumentRow with expanded=false', () => {
      crossIndexServiceSpy.getDocuments.and.returnValue(of(makeDocumentResponse(2)));

      component.loadDocuments();

      expect(component.documents.length).toBe(2);
      expect(component.documents[0].expanded).toBeFalse();
      expect(component.documents[0].passages).toBeUndefined();
    });

    it('should set totalDocuments from response', () => {
      crossIndexServiceSpy.getDocuments.and.returnValue(of(makeDocumentResponse(7)));

      component.loadDocuments();

      expect(component.totalDocuments).toBe(7);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // loadPassages()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadPassages()', () => {
    it('should not reload when passages already loaded', () => {
      const doc = {
        ...makeDocumentItem(1), expanded: true,
        passages: { passages: [], total: 0 } as any,
        loadingPassages: false
      } as any;

      component.loadPassages(doc);

      expect(crossIndexServiceSpy.getPassages).not.toHaveBeenCalled();
    });

    it('should call getPassages with doc id when passages not loaded', () => {
      const doc = {
        ...makeDocumentItem(5), expanded: true, passages: undefined, loadingPassages: false
      } as any;
      const mockPassages: IndexedPassageResponse = { passages: [], total: 0, offset: 0, limit: 50 } as IndexedPassageResponse;
      crossIndexServiceSpy.getPassages.and.returnValue(of(mockPassages));

      component.loadPassages(doc);

      expect(crossIndexServiceSpy.getPassages).toHaveBeenCalledWith(5);
      expect(doc.passages).toEqual(mockPassages);
      expect(doc.loadingPassages).toBeFalse();
    });

    it('should set loadingPassages=false on error', () => {
      const doc = {
        ...makeDocumentItem(3), expanded: true, passages: undefined, loadingPassages: false
      } as any;
      crossIndexServiceSpy.getPassages.and.returnValue(
        throwError(() => new Error('Load error'))
      );

      component.loadPassages(doc);

      expect(doc.loadingPassages).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // refresh()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('refresh()', () => {
    it('should reload summary and documents', () => {
      component.refresh();
      expect(crossIndexServiceSpy.getCrossIndexSummaryForFactSheet).toHaveBeenCalled();
      expect(crossIndexServiceSpy.getDocuments).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // filtering and search
  // ─────────────────────────────────────────────────────────────────────────────

  describe('filtering and search', () => {
    it('should reset to page 0 and reload on status filter change', () => {
      component.currentPage = 3;
      component.onStatusFilterChange();
      expect(component.currentPage).toBe(0);
      expect(crossIndexServiceSpy.getDocuments).toHaveBeenCalled();
    });

    it('should reset to page 0 and reload on search', () => {
      component.currentPage = 2;
      component.onSearch();
      expect(component.currentPage).toBe(0);
      expect(crossIndexServiceSpy.getDocuments).toHaveBeenCalled();
    });

    it('should clear all filters and reload on clearFilters', () => {
      component.statusFilter = 'FULLY_INDEXED' as OverallIndexStatus;
      component.searchQuery = 'test';
      component.currentPage = 5;

      component.clearFilters();

      expect(component.statusFilter).toBe('');
      expect(component.searchQuery).toBe('');
      expect(component.currentPage).toBe(0);
      expect(crossIndexServiceSpy.getDocuments).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // pagination
  // ─────────────────────────────────────────────────────────────────────────────

  describe('pagination', () => {
    beforeEach(() => {
      component.totalDocuments = 60;
      component.pageSize = 20;
      component.currentPage = 0;
    });

    it('should go to next page when more pages exist', () => {
      component.nextPage();
      expect(component.currentPage).toBe(1);
      expect(crossIndexServiceSpy.getDocuments).toHaveBeenCalled();
    });

    it('should not go past last page', () => {
      component.currentPage = 2; // last page for 60 docs / 20 per page
      component.nextPage();
      expect(component.currentPage).toBe(2);
    });

    it('should go to prev page when on page > 0', () => {
      component.currentPage = 2;
      component.prevPage();
      expect(component.currentPage).toBe(1);
    });

    it('should not go before page 0', () => {
      component.currentPage = 0;
      component.prevPage();
      expect(component.currentPage).toBe(0);
    });

    it('should calculate pageStart correctly', () => {
      component.currentPage = 1;
      expect(component.pageStart).toBe(21);
    });

    it('should calculate pageEnd correctly', () => {
      component.currentPage = 0;
      expect(component.pageEnd).toBe(20);
    });

    it('should cap pageEnd at totalDocuments', () => {
      component.currentPage = 2;
      component.totalDocuments = 55;
      expect(component.pageEnd).toBe(55);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // toggleExpand()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('toggleExpand()', () => {
    it('should toggle expanded flag from false to true', () => {
      const doc = {
        ...makeDocumentItem(1), expanded: false, passages: undefined, loadingPassages: false
      } as any;
      crossIndexServiceSpy.getPassages.and.returnValue(of({ passages: [], total: 0 } as any));

      component.toggleExpand(doc);

      expect(doc.expanded).toBeTrue();
    });

    it('should load passages when expanding without existing passages', () => {
      const doc = {
        ...makeDocumentItem(2), expanded: false, passages: undefined, loadingPassages: false
      } as any;
      crossIndexServiceSpy.getPassages.and.returnValue(of({ passages: [], total: 0 } as any));

      component.toggleExpand(doc);

      expect(crossIndexServiceSpy.getPassages).toHaveBeenCalledWith(2);
    });

    it('should collapse without reloading passages when already loaded', () => {
      const doc = {
        ...makeDocumentItem(3), expanded: true,
        passages: { passages: [], total: 0 } as any,
        loadingPassages: false
      } as any;

      component.toggleExpand(doc);

      expect(doc.expanded).toBeFalse();
      expect(crossIndexServiceSpy.getPassages).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // Selection
  // ─────────────────────────────────────────────────────────────────────────────

  describe('selection', () => {
    beforeEach(() => {
      crossIndexServiceSpy.getDocuments.and.returnValue(of(makeDocumentResponse(3)));
      component.loadDocuments();
    });

    it('should select document via toggleSelectDocument', () => {
      const doc = component.documents[0];
      component.toggleSelectDocument(doc);
      expect(component.isSelected(doc)).toBeTrue();
    });

    it('should deselect already selected document', () => {
      const doc = component.documents[0];
      component.toggleSelectDocument(doc);
      component.toggleSelectDocument(doc);
      expect(component.isSelected(doc)).toBeFalse();
    });

    it('should select all via toggleSelectAll when allSelected is false', () => {
      component.allSelected = false;
      component.toggleSelectAll();
      expect(component.selectedDocumentIds.size).toBe(3);
      expect(component.allSelected).toBeTrue();
    });

    it('should deselect all via toggleSelectAll when allSelected is true', () => {
      component.allSelected = true;
      component.documents.forEach(d => component.selectedDocumentIds.add(d.id));
      component.toggleSelectAll();
      expect(component.selectedDocumentIds.size).toBe(0);
      expect(component.allSelected).toBeFalse();
    });

    it('should report hasSelection when documents selected', () => {
      const doc = component.documents[0];
      component.toggleSelectDocument(doc);
      expect(component.hasSelection).toBeTrue();
    });

    it('should report hasSelection=false when nothing selected', () => {
      expect(component.hasSelection).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // Sync operations
  // ─────────────────────────────────────────────────────────────────────────────

  describe('sync operations', () => {
    beforeEach(() => {
      crossIndexServiceSpy.syncToVectorStore.and.returnValue(of(makeSyncJobResponse('job-vs')));
      crossIndexServiceSpy.syncToKnowledgeGraph.and.returnValue(of(makeSyncJobResponse('job-kg')));
      crossIndexServiceSpy.syncAll.and.returnValue(of(makeSyncJobResponse('job-all')));
      crossIndexServiceSpy.syncDocuments.and.returnValue(of(makeSyncJobResponse('job-docs')));
    });

    it('syncToVectorStore should call service with factSheetId', () => {
      component.syncToVectorStore();
      expect(crossIndexServiceSpy.syncToVectorStore).toHaveBeenCalledWith(1);
    });

    it('syncToKnowledgeGraph should call service', () => {
      component.syncToKnowledgeGraph();
      expect(crossIndexServiceSpy.syncToKnowledgeGraph).toHaveBeenCalledWith(1);
    });

    it('syncAll should call service', () => {
      component.syncAll();
      expect(crossIndexServiceSpy.syncAll).toHaveBeenCalledWith(1);
    });

    it('syncSelectedToVector should do nothing when no selection', () => {
      component.syncSelectedToVector();
      expect(crossIndexServiceSpy.syncDocuments).not.toHaveBeenCalled();
    });

    it('syncSelectedToVector should call syncDocuments with selected ids', () => {
      component.selectedDocumentIds.add(1);
      component.selectedDocumentIds.add(2);

      component.syncSelectedToVector();

      expect(crossIndexServiceSpy.syncDocuments).toHaveBeenCalledWith(
        jasmine.arrayContaining([1, 2]),
        ['VECTOR_STORE']
      );
    });

    it('syncSelectedToGraph should call syncDocuments with KNOWLEDGE_GRAPH target', () => {
      component.selectedDocumentIds.add(3);

      component.syncSelectedToGraph();

      expect(crossIndexServiceSpy.syncDocuments).toHaveBeenCalledWith(
        [3], ['KNOWLEDGE_GRAPH']
      );
    });

    it('syncSelectedToAll should call syncDocuments with both targets', () => {
      component.selectedDocumentIds.add(4);

      component.syncSelectedToAll();

      expect(crossIndexServiceSpy.syncDocuments).toHaveBeenCalledWith(
        [4], ['VECTOR_STORE', 'KNOWLEDGE_GRAPH']
      );
    });

    it('should set syncInProgress=true when sync starts', () => {
      component.syncToVectorStore();
      expect(component.syncInProgress).toBeTrue();
    });

    it('should handle sync start error gracefully', () => {
      crossIndexServiceSpy.syncToVectorStore.and.returnValue(
        throwError(() => new Error('Sync start failed'))
      );

      component.syncToVectorStore();

      expect(component.syncInProgress).toBeFalse();
      expect(component.activeSyncJob).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // cancelSync()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('cancelSync()', () => {
    it('should call cancelSyncJob with active job id', () => {
      component.activeSyncJob = { jobId: 'active-job', status: 'RUNNING', progress: 50, message: '' };
      crossIndexServiceSpy.cancelSyncJob.and.returnValue(of(undefined));

      component.cancelSync();

      expect(crossIndexServiceSpy.cancelSyncJob).toHaveBeenCalledWith('active-job');
    });

    it('should do nothing when no active sync job', () => {
      component.activeSyncJob = null;
      component.cancelSync();
      expect(crossIndexServiceSpy.cancelSyncJob).not.toHaveBeenCalled();
    });

    it('should update activeSyncJob status to CANCELLED on success', () => {
      component.activeSyncJob = { jobId: 'job-1', status: 'RUNNING', progress: 30, message: '' };
      crossIndexServiceSpy.cancelSyncJob.and.returnValue(of(undefined));

      component.cancelSync();

      expect(component.activeSyncJob?.status).toBe('CANCELLED');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // pollSyncStatus — uses interval, test with fakeAsync
  // ─────────────────────────────────────────────────────────────────────────────

  describe('pollSyncStatus (via syncToVectorStore)', () => {
    it('should stop polling after COMPLETED status', fakeAsync(() => {
      crossIndexServiceSpy.syncToVectorStore.and.returnValue(
        of({ jobId: 'job-poll', status: 'STARTED', message: 'OK' } as SyncJobResponse)
      );
      crossIndexServiceSpy.getSyncJobStatus.and.returnValue(of({
        jobId: 'job-poll', status: 'COMPLETED', progress: 100, message: 'Done'
      } as SyncJobStatusResponse));

      component.syncToVectorStore();
      tick(1000); // trigger one interval tick
      tick(2001); // allow the setTimeout inside handleCompletion

      expect(component.syncInProgress).toBeFalse();

      discardPeriodicTasks();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // Utility methods
  // ─────────────────────────────────────────────────────────────────────────────

  describe('formatDate()', () => {
    it('should return Never for empty string', () => {
      expect(component.formatDate('')).toBe('Never');
    });

    it('should return formatted date string for valid ISO date', () => {
      const result = component.formatDate('2025-01-15T10:00:00Z');
      expect(result).not.toBe('Never');
      expect(result.length).toBeGreaterThan(0);
    });
  });

  describe('truncateSourceId()', () => {
    it('should return empty string for empty input', () => {
      expect(component.truncateSourceId('')).toBe('');
    });

    it('should return original string if <= 50 chars', () => {
      const short = 'abc-123';
      expect(component.truncateSourceId(short)).toBe(short);
    });

    it('should truncate long IDs with ellipsis prefix', () => {
      const long = 'a'.repeat(60);
      const result = component.truncateSourceId(long);
      expect(result.startsWith('...')).toBeTrue();
      expect(result.length).toBe(50);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // Exposed helper functions
  // ─────────────────────────────────────────────────────────────────────────────

  describe('exposed helper functions', () => {
    it('should have getCrossIndexStatusColor function', () => {
      expect(typeof component.getCrossIndexStatusColor).toBe('function');
    });

    it('should have getCrossIndexStatusIcon function', () => {
      expect(typeof component.getCrossIndexStatusIcon).toBe('function');
    });

    it('should have getCrossIndexStatusLabel function', () => {
      expect(typeof component.getCrossIndexStatusLabel).toBe('function');
    });

    it('should have getIndexStatusColor function', () => {
      expect(typeof component.getIndexStatusColor).toBe('function');
    });

    it('should have getIndexStatusIcon function', () => {
      expect(typeof component.getIndexStatusIcon).toBe('function');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // ngOnDestroy
  // ─────────────────────────────────────────────────────────────────200────────────
  // ─────────────────────────────────────────────────────────────────────────────

  describe('ngOnDestroy()', () => {
    it('should complete the destroy$ subject without errors', () => {
      expect(() => component.ngOnDestroy()).not.toThrow();
    });
  });
});
