import { Component, OnInit, ViewChild, ChangeDetectorRef, AfterViewInit } from '@angular/core';
import { MatTableDataSource } from '@angular/material/table';
import { MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSort } from '@angular/material/sort';
import { IndexBrowserService } from '../../services/index-browser.service';
import { IndexedDocInfo } from '../../models/api-models';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CdkTextareaAutosize } from '@angular/cdk/text-field';

@Component({
  selector: 'app-index-browser',
  templateUrl: './index-browser.component.html',
  styleUrls: ['./index-browser.component.css']
})
export class IndexBrowserComponent implements OnInit, AfterViewInit {
  dataSource = new MatTableDataSource<IndexedDocInfo>();
  displayedColumns: string[] = ['id', 'preview', 'actions'];

  isLoading = false;
  totalDocsEstimate = 0;
  pageSize = 10;
  currentPage = 0;

  selectedDoc: IndexedDocInfo | null = null;
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
    this.loadDocuments();
  }

  ngAfterViewInit() {
    if (this.paginator) {
      this.dataSource.paginator = this.paginator;
      this.paginator.page.subscribe((event: PageEvent) => {
        this.pageSize = event.pageSize;
        this.currentPage = event.pageIndex;
        this.loadDocuments(this.currentPage * this.pageSize, this.pageSize);
      });
    }
    if(this.sort) {
      this.dataSource.sort = this.sort;
    }
  }

  loadDocuments(offset: number = 0, limit: number = this.pageSize): void {
    this.isLoading = true;
    this.selectedDoc = null;
    this.indexBrowserService.getAllIndexedDocs(offset, limit).subscribe({
      next: (docs) => {
        this.dataSource.data = docs;
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

  viewDocument(doc: IndexedDocInfo): void {
    this.isLoading = true;
    this.indexBrowserService.getIndexedDoc(doc.id).subscribe({
      next: (fullDoc) => {
        this.selectedDoc = fullDoc;
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
  objectKeys = Object.keys;
}
