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
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, BehaviorSubject } from 'rxjs';

import { IndexBrowserComponent } from './index-browser.component';
import { IndexBrowserService } from '../../services/index-browser.service';
import { CrossIndexService } from '../../services/cross-index.service';
import { GraphService } from '../../services/graph.service';
import { FactSheetService } from '../../services/fact-sheet.service';

describe('IndexBrowserComponent', () => {
  let component: IndexBrowserComponent;
  let fixture: ComponentFixture<IndexBrowserComponent>;

  const mockIndexBrowserService = {
    getIndexBrowserStatus: () => of({
      indexAvailable: true,
      vectorStoreAvailable: true,
      approximateDocumentCount: 10,
      approximateVectorCount: 8,
      isNoOpIndexer: false,
      isNoOpVectorStore: false,
      isUsingFallbackIndex: false
    }),
    searchIndexedDocs: () => of({ results: [], totalResults: 0 }),
    searchVectorStore: () => of({ results: [], totalResults: 0 })
  };

  const mockCrossIndexService = {
    getCrossIndexSummaryForFactSheet: () => of({
      totalDocuments: 5,
      totalPassages: 20,
      fullyIndexedDocuments: 3,
      documentsNeedingSync: 1
    }),
    getDocuments: () => of({ documents: [], total: 0, offset: 0, limit: 20 }),
    getDocumentDetail: () => of({}),
    getPassages: () => of({ passages: [], total: 0, offset: 0, limit: 50 })
  };

  const mockGraphService = {
    getNodes: () => of([])
  };

  const activeSheetSubject = new BehaviorSubject<any>({
    id: 1,
    name: 'Test Sheet',
    icon: 'folder',
    color: '#1976d2',
    factCount: 5
  });

  const mockFactSheetService = {
    activeSheet$: activeSheetSubject.asObservable(),
    getActiveSheet: () => activeSheetSubject.value
  };

  const mockSnackBar = {
    open: jasmine.createSpy('open')
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [IndexBrowserComponent],
      imports: [ReactiveFormsModule, NoopAnimationsModule],
      providers: [
        { provide: IndexBrowserService, useValue: mockIndexBrowserService },
        { provide: CrossIndexService, useValue: mockCrossIndexService },
        { provide: GraphService, useValue: mockGraphService },
        { provide: FactSheetService, useValue: mockFactSheetService },
        { provide: MatSnackBar, useValue: mockSnackBar }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(IndexBrowserComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should default to documents tab', () => {
    expect(component.activeTab).toBe('documents');
  });

  it('should switch tabs', () => {
    component.selectTab('entities');
    expect(component.activeTab).toBe('entities');
  });

  it('should load index status on init', () => {
    expect(component.indexBrowserStatus).toBeTruthy();
    expect(component.indexBrowserStatus?.indexAvailable).toBeTrue();
  });

  it('should track active fact sheet', () => {
    expect(component.activeSheet).toBeTruthy();
    expect(component.activeSheet?.name).toBe('Test Sheet');
  });

  it('should return correct status icons', () => {
    expect(component.getStatusIcon('FULLY_INDEXED')).toBe('check_circle');
    expect(component.getStatusIcon('NOT_INDEXED')).toBe('radio_button_unchecked');
    expect(component.getStatusIcon('FAILED')).toBe('error');
  });

  it('should return correct node type icons', () => {
    expect(component.getNodeTypeIcon('ENTITY')).toBe('label');
    expect(component.getNodeTypeIcon('TABLE')).toBe('table_chart');
    expect(component.getNodeTypeIcon('DOCUMENT')).toBe('description');
  });

  it('should truncate content correctly', () => {
    const short = 'hello';
    expect(component.truncateContent(short, 10)).toBe('hello');

    const long = 'a'.repeat(200);
    expect(component.truncateContent(long, 10)).toBe('a'.repeat(10) + '...');
  });

  it('should return correct score classes', () => {
    expect(component.getScoreClass(0.9)).toBe('high-score');
    expect(component.getScoreClass(0.6)).toBe('medium-score');
    expect(component.getScoreClass(0.2)).toBe('low-score');
  });
});
