import { Component, OnInit, ViewChild, ChangeDetectorRef, AfterViewInit } from '@angular/core';
import { MatTableDataSource } from '@angular/material/table';
import { MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSort } from '@angular/material/sort';
import { FormControl } from '@angular/forms';
import { IndexBrowserService } from '../../services/index-browser.service';
import { IndexedDocInfo, SearchResult, SearchResponse, IndexBrowserStatus } from '../../models/api-models';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CdkTextareaAutosize } from '@angular/cdk/text-field';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';

interface DisplayItem {
  id: string;
  preview: string;
  score?: number;
  originalDocument?: string;
  metadata?: { [key: string]: any };
  lucene_internal_id?: number;
  content?: string;
  isSearchResult?: boolean;
}

@Component({
  selector: 'app-index-browser',
  standalone: false,
  templateUrl: './index-browser.component.html',
  styleUrls: ['./index-browser.component.css']
})
export class IndexBrowserComponent implements OnInit, AfterViewInit {
  dataSource = new MatTableDataSource<DisplayItem>();
  displayedColumns: string[] = ['id', 'preview', 'score', 'originalDocument', 'actions'];
  
  // Search functionality
  searchControl = new FormControl('');
  searchResults: SearchResult[] = [];
  isSearchMode: boolean = false;
  currentSearchQuery: string = '';
  maxSearchResults: number = 20;

  // Status information
  indexBrowserStatus: IndexBrowserStatus | null = null;
  isLoadingStatus: boolean = false;

  // General state
  isLoading = false;
  totalDocsEstimate = 0;
  pageSize = 10;
  currentPage = 0;

  // Document details
  selectedDoc: DisplayItem | null = null;
  editedContent: string = '';

  @ViewChild(MatPaginator) paginator!: MatPaginator;
  @ViewChild(MatSort) sort!: MatSort;
  @ViewChild('autosize') autosize!: CdkTextareaAutosize;

  constructor(
    private indexBrowserService: IndexBrowserService,
    private cdr: ChangeDetectorRef,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadStatus();
    this.loadDocuments();
    this.setupSearchControl();
  }

  ngAfterViewInit() {
    if (this.paginator) {
      this.dataSource.paginator = this.paginator;
      this.paginator.page.subscribe((event: PageEvent) => {
        if (!this.isSearchMode) {
          this.pageSize = event.pageSize;
          this.currentPage = event.pageIndex;
          this.loadDocuments(this.currentPage * this.pageSize, this.pageSize);
        }
      });
    }
    if(this.sort) {
      this.dataSource.sort = this.sort;
    }
  }

  loadStatus(): void {
    this.isLoadingStatus = true;
    this.indexBrowserService.getIndexBrowserStatus().subscribe({
      next: (status) => {
        this.indexBrowserStatus = status;
        this.isLoadingStatus = false;
        this.cdr.detectChanges();
        
        if (status.warning) {
          this.snackBar.open(status.warning, 'Close', {
            duration: 10000,
            panelClass: ['snackbar-warning']
          });
        }
      },
      error: (err) => {
        this.isLoadingStatus = false;
        this.snackBar.open(`Error loading status: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  setupSearchControl(): void {
    this.searchControl.valueChanges.pipe(
      debounceTime(300),
      distinctUntilChanged()
    ).subscribe(query => {
      if (query && query.trim().length > 0) {
        this.performSearch(query.trim());
      } else {
        this.clearSearch();
      }
    });
  }

  performSearch(query: string): void {
    this.isLoading = true;
    this.isSearchMode = true;
    this.currentSearchQuery = query;
    this.selectedDoc = null;
    
    this.indexBrowserService.searchIndexedDocs(query, this.maxSearchResults).subscribe({
      next: (response: SearchResponse) => {
        this.searchResults = response.results;
        this.updateDataSourceWithSearchResults(response.results);
        this.isLoading = false;
        this.cdr.detectChanges();
        
        this.snackBar.open(`Found ${response.totalResults} results for "${query}"`, 'Close', {
          duration: 3000,
          panelClass: ['snackbar-success']
        });
      },
      error: (err) => {
        this.isLoading = false;
        this.isSearchMode = false;
        this.snackBar.open(`Search failed: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  clearSearch(): void {
    this.isSearchMode = false;
    this.currentSearchQuery = '';
    this.searchResults = [];
    this.searchControl.setValue('', { emitEvent: false });
    this.loadDocuments();
  }

  updateDataSourceWithSearchResults(results: SearchResult[]): void {
    const displayItems: DisplayItem[] = results.map(result => ({
      id: result.id,
      preview: result.preview,
      score: result.score,
      originalDocument: result.originalDocument,
      metadata: result.metadata,
      content: result.content,
      isSearchResult: true
    }));
    
    this.dataSource.data = displayItems;
    this.totalDocsEstimate = displayItems.length;
    
    // Disable pagination for search results
    if (this.paginator) {
      this.paginator.length = displayItems.length;
      this.paginator.pageIndex = 0;
    }
  }

  loadDocuments(offset: number = 0, limit: number = this.pageSize): void {
    this.isLoading = true;
    this.selectedDoc = null;
    this.indexBrowserService.getAllIndexedDocs(offset, limit).subscribe({
      next: (docs) => {
        const displayItems: DisplayItem[] = docs.map(doc => ({
          id: doc.id,
          preview: doc.preview || '[No preview]',
          metadata: doc.metadata,
          lucene_internal_id: doc.lucene_internal_id,
          content: doc.content,
          isSearchResult: false
        }));
        
        this.dataSource.data = displayItems;
        if (docs.length < limit) {
          this.totalDocsEstimate = offset + docs.length;
        } else {
          this.totalDocsEstimate = offset + docs.length + 1;
        }
        if(this.paginator) {
          this.paginator.length = this.totalDocsEstimate;
        }
        this.isLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.isLoading = false;
        this.snackBar.open(`Error loading documents: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  viewDocument(doc: DisplayItem): void {
    if (doc.isSearchResult && doc.content) {
      // For search results, we already have the content
      this.selectedDoc = doc;
      this.editedContent = doc.content || '';
      this.cdr.detectChanges();
    } else {
      // For browse results, we need to fetch the full document
      this.isLoading = true;
      this.indexBrowserService.getIndexedDoc(doc.id).subscribe({
        next: (fullDoc) => {
          this.selectedDoc = {
            ...doc,
            content: fullDoc.content,
            metadata: fullDoc.metadata
          };
          this.editedContent = fullDoc.content || '';
          this.isLoading = false;
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isLoading = false;
          this.snackBar.open(`Error loading document ${doc.id}: ${err.message || 'Server error'}`, 'Close', {
            duration: 5000,
            panelClass: ['snackbar-error']
          });
        }
      });
    }
  }

  closeDocumentView(): void {
    this.selectedDoc = null;
    this.editedContent = '';
  }

  saveDocument(): void {
    if (!this.selectedDoc || this.editedContent === null) return;
    this.isLoading = true;
    this.indexBrowserService.updateIndexedDoc(this.selectedDoc.id, this.editedContent).subscribe({
      next: (response) => {
        this.snackBar.open(response.message || `Document ${this.selectedDoc?.id} updated.`, 'Close', {
          duration: 3000,
          panelClass: ['snackbar-success']
        });
        this.isLoading = false;
        if (this.selectedDoc) {
          this.viewDocument(this.selectedDoc);
        }
      },
      error: (err) => {
        this.isLoading = false;
        this.snackBar.open(`Error updating document ${this.selectedDoc?.id}: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  refreshData(): void {
    this.loadStatus();
    if (this.isSearchMode && this.currentSearchQuery) {
      this.performSearch(this.currentSearchQuery);
    } else {
      this.loadDocuments(this.currentPage * this.pageSize, this.pageSize);
    }
  }

  getDisplayedColumnsForMode(): string[] {
    if (this.isSearchMode) {
      return ['id', 'preview', 'score', 'originalDocument', 'actions'];
    } else {
      return ['id', 'preview', 'actions'];
    }
  }

  formatScore(score: number | undefined): string {
    return score !== undefined ? score.toFixed(3) : 'N/A';
  }

  getStatusColor(): string {
    if (!this.indexBrowserStatus) return 'warn';
    
    if (this.indexBrowserStatus.isNoOpIndexer || this.indexBrowserStatus.isNoOpRetriever) {
      return 'warn';
    }
    
    if (!this.indexBrowserStatus.indexAvailable) {
      return 'warn';
    }
    
    return 'primary';
  }

  getStatusMessage(): string {
    if (!this.indexBrowserStatus) return 'Loading status...';
    
    if (this.indexBrowserStatus.isNoOpIndexer && this.indexBrowserStatus.isNoOpRetriever) {
      return 'Using NoOp implementations - no functionality available';
    }
    
    if (this.indexBrowserStatus.isNoOpIndexer) {
      return 'Using NoOp Indexer - document browsing not available';
    }
    
    if (this.indexBrowserStatus.isNoOpRetriever) {
      return 'Using NoOp Retriever - search functionality not available';
    }
    
    if (!this.indexBrowserStatus.indexAvailable) {
      return 'Index not available - may need to be built';
    }
    
    return 'Index available and ready';
  }

  objectKeys = Object.keys;
}
