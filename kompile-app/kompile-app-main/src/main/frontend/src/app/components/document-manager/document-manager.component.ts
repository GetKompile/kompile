/*
 * Copyright 2025 Kompile Inc.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 */

import { Component, OnInit, ChangeDetectorRef, ViewChild, AfterViewInit } from '@angular/core';
import { DocumentService } from '../../services/document.service';
import { AnseriniService } from '../../services/anserini.service';
import {
  AddUrlRequest,
  FileUploadResponse,
  SimpleMessageResponse,
  LoaderInfo,
  ChunkerInfo,
  BatchProcessRequest,
  BatchLoadRequestItem,
  DocumentSourceType,
  BatchProcessResponse,
  BatchProcessResponseDetails,
  AddSourceDialogResult,
  DocumentSummary
} from '../../models/api-models';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource } from '@angular/material/table';
import { MatPaginator } from '@angular/material/paginator';
import { MatSort } from '@angular/material/sort';
import { AddSourceDialogComponent } from './add-source-dialog/add-source-dialog.component';
import { forkJoin, of, Observable } from 'rxjs';
import { catchError, finalize, map } from 'rxjs/operators';

export interface ConfiguredSourceElement {
  id: number;
  path: string;
  type: 'Property' | 'Upload Path';
}

export interface UploadedFileElement {
  id: number;
  name: string;
}

type UploadedFilesData = { uploaded_files_location: string; files: string[] };

@Component({
  standalone: false,
  selector: 'app-document-manager',
  templateUrl: './document-manager.component.html',
  styleUrls: ['./document-manager.component.css']
})
export class DocumentManagerComponent implements OnInit, AfterViewInit {
  configuredSourcesDataSource = new MatTableDataSource<ConfiguredSourceElement>();
  displayedColumnsConfiguredSources: string[] = ['id', 'path', 'type'];

  uploadedFilesDataSource = new MatTableDataSource<UploadedFileElement>();
  displayedColumnsUploadedFiles: string[] = ['id', 'name', 'actions'];

  @ViewChild('configuredSourcesPaginator') configuredSourcesPaginator!: MatPaginator;
  @ViewChild('configuredSourcesSort') configuredSourcesSort!: MatSort;
  @ViewChild('uploadedFilesPaginator') uploadedFilesPaginator!: MatPaginator;
  @ViewChild('uploadedFilesSort') uploadedFilesSort!: MatSort;

  availableLoaders: LoaderInfo[] = [];
  availableChunkers: ChunkerInfo[] = [];

  private _configuredSources: string[] = [];
  private _uploadedFiles: string[] = [];
  uploadedFilesLocation: string = 'N/A';

  batchSourcePaths: string = '';
  batchSelectedLoader: string = '';
  isBatchProcessing: boolean = false;
  batchResults: BatchProcessResponseDetails | null = null;
  batchResultPaths: string[] = [];

  isLoading: boolean = false;

  showLoadersSpinner: boolean = false;
  showNoLoadersMessage: boolean = false;
  showLoadersList: boolean = false;
  showChunkersSpinner: boolean = false;
  showNoChunkersMessage: boolean = false;
  showChunkersList: boolean = false;
  showConfiguredSourcesSpinner: boolean = false;
  showNoConfiguredSourcesMessage: boolean = false;
  showConfiguredSourcesTable: boolean = false;
  showUploadedFilesSpinner: boolean = false;
  showNoUploadedFilesMessage: boolean = false;
  showUploadedFilesTable: boolean = false;
  showBatchResults: boolean = false;

  constructor(
    private documentService: DocumentService,
    private anseriniService: AnseriniService,
    public dialog: MatDialog,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) { }

  ngOnInit(): void {
    this.refreshAllData();
  }

  ngAfterViewInit(): void {
    this.assignPaginatorsAndSorters();
  }

  private assignPaginatorsAndSorters(): void {
    if (this.configuredSourcesPaginator && this.configuredSourcesDataSource.paginator !== this.configuredSourcesPaginator) {
      this.configuredSourcesDataSource.paginator = this.configuredSourcesPaginator;
    }
    if (this.configuredSourcesSort && this.configuredSourcesDataSource.sort !== this.configuredSourcesSort) {
      this.configuredSourcesDataSource.sort = this.configuredSourcesSort;
    }
    if (this.uploadedFilesPaginator && this.uploadedFilesDataSource.paginator !== this.uploadedFilesPaginator) {
      this.uploadedFilesDataSource.paginator = this.uploadedFilesPaginator;
    }
    if (this.uploadedFilesSort && this.uploadedFilesDataSource.sort !== this.uploadedFilesSort) {
      this.uploadedFilesDataSource.sort = this.uploadedFilesSort;
    }
  }

  private updateTemplateFlags(): void {
    this.showLoadersSpinner = this.isLoading && (!this.availableLoaders || this.availableLoaders.length === 0);
    this.showNoLoadersMessage = !this.isLoading && (!this.availableLoaders || this.availableLoaders.length === 0);
    this.showLoadersList = !!(this.availableLoaders && this.availableLoaders.length > 0);

    this.showChunkersSpinner = this.isLoading && (!this.availableChunkers || this.availableChunkers.length === 0);
    this.showNoChunkersMessage = !this.isLoading && (!this.availableChunkers || this.availableChunkers.length === 0);
    this.showChunkersList = !!(this.availableChunkers && this.availableChunkers.length > 0);

    this.showConfiguredSourcesSpinner = this.isLoading && this.configuredSourcesDataSource.data.length === 0;
    this.showNoConfiguredSourcesMessage = !this.isLoading && this.configuredSourcesDataSource.data.length === 0;
    this.showConfiguredSourcesTable = this.configuredSourcesDataSource.data.length > 0;

    this.showUploadedFilesSpinner = this.isLoading && this.uploadedFilesDataSource.data.length === 0;
    this.showNoUploadedFilesMessage = !this.isLoading && this.uploadedFilesDataSource.data.length === 0;
    this.showUploadedFilesTable = this.uploadedFilesDataSource.data.length > 0;

    this.showBatchResults = !!(this.batchResults && !this.isBatchProcessing && this.batchResultPaths.length > 0);
    this.cdr.detectChanges();
  }

  refreshAllData(callback?: () => void): void {
    if (this.isLoading && !callback) {
      return;
    }
    this.isLoading = true;
    this.updateTemplateFlags();

    const loaders$: Observable<LoaderInfo[]> = this.documentService.getAvailableLoaders().pipe(catchError(err => {
      this.handleLoadError('available loaders', err);
      return of([] as LoaderInfo[]);
    }));
    const chunkers$: Observable<ChunkerInfo[]> = this.documentService.getAvailableChunkers().pipe(catchError(err => {
      this.handleLoadError('available chunkers', err);
      return of([] as ChunkerInfo[]);
    }));
    const uploadedFiles$: Observable<UploadedFilesData> = this.documentService.getUploadedFiles().pipe(catchError(err => {
      this.handleLoadError('uploaded files', err);
      return of({ files: [], uploaded_files_location: 'Error loading path' } as UploadedFilesData);
    }));
    const configuredSources$: Observable<string[]> = this.documentService.getConfiguredSources().pipe(catchError(err => {
      this.handleLoadError('configured sources', err);
      return of([] as string[]);
    }));

    forkJoin([loaders$, chunkers$, uploadedFiles$, configuredSources$]).pipe(
      finalize(() => {
        this.isLoading = false;
        this.updateTemplateFlags();
        if (callback) {
          callback();
        }
      })
    ).subscribe({
      next: ([loaders, chunkers, uploadedFilesData, sources]: [LoaderInfo[], ChunkerInfo[], UploadedFilesData, string[]]) => {
        this.availableLoaders = loaders || [];
        this.availableChunkers = chunkers || [];
        this._uploadedFiles = uploadedFilesData?.files || [];
        this.uploadedFilesLocation = uploadedFilesData?.uploaded_files_location || 'N/A';
        this.updateUploadedFilesTable();
        this._configuredSources = sources || [];
        this.updateConfiguredSourcesTable();
      },
      error: (err) => {
        this.showSnackbar(`An error occurred while loading initial data: ${err.message || 'Server error'}`, true);
      }
    });
  }

  private handleLoadError(dataType: string, error: HttpErrorResponse) {
    this.showSnackbar(`Error loading ${dataType}: ${error.message || 'Server error'}`, true);
    if (dataType === 'available loaders') this.availableLoaders = [];
    if (dataType === 'available chunkers') this.availableChunkers = [];
    if (dataType === 'uploaded files') {
      this._uploadedFiles = []; this.uploadedFilesLocation = 'Error loading path'; this.updateUploadedFilesTable();
    }
    if (dataType === 'configured sources') {
      this._configuredSources = []; this.updateConfiguredSourcesTable();
    }
    // this.updateTemplateFlags(); // Not calling here, finalize in refreshAllData will call it
  }

  getSimpleName(className: any): string {
    const nameStr = this.safeToString(className);
    const parts = nameStr.split('.');
    return parts.pop() || '';
  }

  get hasBatchSourceContent(): boolean {
    return  this.batchSourcePaths.trim().length > 0;
  }

  get isProcessBatchDisabled(): boolean {
    return this.isBatchProcessing || !this.hasBatchSourceContent || !this.batchSelectedLoader;
  }

  hasMetadata(summary: DocumentSummary | undefined | null): boolean {
    return this.hasValidMetadata(summary);
  }

  // Enhanced method to safely check if metadata exists and is valid
  hasValidMetadata(summary: DocumentSummary | undefined | null): boolean {
    if (!summary || !summary.metadata) return false;
    
    try {
      // Test if metadata can be JSON stringified without circular references
      JSON.stringify(summary.metadata);
      return Object.keys(summary.metadata).length > 0;
    } catch (error) {
      console.warn('Metadata contains circular references or is invalid:', error);
      return false;
    }
  }

  // Safe metadata getter
  getSafeMetadata(summary: DocumentSummary | undefined | null): any {
    if (!this.hasValidMetadata(summary)) {
      return {};
    }
    
    try {
      // Return a clean copy to avoid circular reference issues
      return JSON.parse(JSON.stringify(summary!.metadata));
    } catch (error) {
      console.warn('Error creating safe metadata copy:', error);
      return {};
    }
  }

  getBatchResultCount(path: string): number {
    return this.batchResults?.[path]?.count || 0;
  }

  getBatchResultSummaries(path: string): DocumentSummary[] {
    return this.batchResults?.[path]?.summaries || [];
  }

  getBatchResultError(path: string): string | undefined {
    return this.batchResults?.[path]?.error;
  }

  // Enhanced safeToString method
  safeToString(value: any): string {
    if (value === null || value === undefined) {
      return '';
    }
    if (typeof value === 'string') {
      return value;
    }
    if (typeof value === 'number' || typeof value === 'boolean') {
      return String(value);
    }
    // Handle objects and arrays safely
    try {
      return String(value);
    } catch (error) {
      console.warn('Error converting value to string:', error);
      return '';
    }
  }

  // Utility to safely get length of a string-like value
  safeLength(value: any): number {
    return this.safeToString(value).length;
  }

  updateConfiguredSourcesTable(): void {
    const tableData: ConfiguredSourceElement[] = (this._configuredSources || [])
      .filter(s => s && !s.toLowerCase().includes("no primary document sources configured"))
      .map((s, index) => ({ id: index + 1, path: this.safeToString(s), type: 'Property' }));

    if (this.uploadedFilesLocation && this.uploadedFilesLocation !== 'N/A' && !this.uploadedFilesLocation.includes("error_uploads_path_not_configured")) {
      if (!tableData.some(item => item.path === this.uploadedFilesLocation && item.type === 'Upload Path')) {
        tableData.push({
          id: tableData.length + 1,
          path: this.safeToString(this.uploadedFilesLocation),
          type: 'Upload Path'
        });
      }
    }
    this.configuredSourcesDataSource.data = tableData;
    if (this.configuredSourcesPaginator) { this.configuredSourcesDataSource.paginator = this.configuredSourcesPaginator; }
    if (this.configuredSourcesSort) { this.configuredSourcesDataSource.sort = this.configuredSourcesSort; }
    this.updateTemplateFlags();
  }

  updateUploadedFilesTable(): void {
    this.uploadedFilesDataSource.data = (this._uploadedFiles || []).map((f, index) => ({
      id: index + 1,
      name: this.safeToString(f),
    }));
    if (this.uploadedFilesPaginator) { this.uploadedFilesDataSource.paginator = this.uploadedFilesPaginator; }
    if (this.uploadedFilesSort) { this.uploadedFilesDataSource.sort = this.uploadedFilesSort; }
    this.updateTemplateFlags();
  }

  applyFilterConfigured(event: Event) {
    const filterValue = (event.target as HTMLInputElement).value;
    this.configuredSourcesDataSource.filter = filterValue.trim().toLowerCase();
    if (this.configuredSourcesDataSource.paginator) this.configuredSourcesDataSource.paginator.firstPage();
  }

  applyFilterUploaded(event: Event) {
    const filterValue = (event.target as HTMLInputElement).value;
    this.uploadedFilesDataSource.filter = filterValue.trim().toLowerCase();
    if (this.uploadedFilesDataSource.paginator) this.uploadedFilesDataSource.paginator.firstPage();
  }

  openAddSourceDialog(): void {
    const dialogRef = this.dialog.open(AddSourceDialogComponent, {
      width: '600px', data: { availableLoaders: this.availableLoaders }, disableClose: true
    });
    dialogRef.afterClosed().subscribe((result: AddSourceDialogResult | undefined) => {
      if (result) this.submitDocumentSource(result);
    });
  }

  private submitDocumentSource(data: AddSourceDialogResult): void {
    this.isLoading = true; this.updateTemplateFlags();
    const handleSuccess = (response: FileUploadResponse | SimpleMessageResponse, operation: string) => {
      this.showSnackbar(response.message || `${operation} successful!`);
      this.refreshAllData(() => {
        if (data?.rebuildIndex) this.onRebuildIndex();
        else { this.isLoading = false; this.updateTemplateFlags(); }
      });
    };
    const handleError = (operation: string, err: HttpErrorResponse) => {
      this.handleOperationError(operation, err); this.isLoading = false; this.updateTemplateFlags();
    };
    if (data.file) {
      this.documentService.uploadFile(data.file, data.selectedLoader).subscribe({
        next: (r) => handleSuccess(r, 'File upload'), error: (e) => handleError('File upload', e)
      });
    } else if (data.url) {
      const request: AddUrlRequest = { url: data.url, fileName: data.fileName, loader: data.selectedLoader };
      this.documentService.addUrl(request).subscribe({
        next: (r) => handleSuccess(r, 'Add URL'), error: (e) => handleError('Add URL', e)
      });
    } else { this.isLoading = false; this.updateTemplateFlags(); }
  }

  onProcessBatch(): void {
    if (!this.hasBatchSourceContent) { this.showSnackbar('Please enter source paths...', true); return; }
    if (!this.batchSelectedLoader) { this.showSnackbar('Please select a loader...', true); return; }
    this.isBatchProcessing = true; this.batchResults = null; this.batchResultPaths = []; this.updateTemplateFlags();
    const items: BatchLoadRequestItem[] = this.batchSourcePaths.split(',').map(p => p.trim()).filter(p => p)
      .map(pathOrUrl => ({ pathOrUrl, type: pathOrUrl.toLowerCase().startsWith('http') ? DocumentSourceType.URL : DocumentSourceType.FILE, loaderName: this.batchSelectedLoader }));
    if (items.length === 0) {
      this.showSnackbar("No valid source paths...", true); this.isBatchProcessing = false; this.updateTemplateFlags(); return;
    }
    this.documentService.processBatch({ items, defaultLoaderName: this.batchSelectedLoader }).pipe(
      finalize(() => { this.isBatchProcessing = false; this.updateTemplateFlags(); })
    ).subscribe({
      next: (res) => {
        this.batchResults = res.details || null; this.batchResultPaths = this.batchResults ? Object.keys(this.batchResults) : [];
        this.showSnackbar(`${res.message || 'Batch complete.'} OK: ${res.successful_items}, Fail: ${res.failed_items}.`);
        this.batchSourcePaths = '';
      },
      error: (err) => { this.handleOperationError('Batch processing', err); this.batchResults = null; this.batchResultPaths = []; }
    });
  }

  onRebuildIndex(): void {
    this.isLoading = true; this.updateTemplateFlags();
    this.anseriniService?.rebuildIndex().pipe(
      finalize(() => { this.isLoading = false; this.updateTemplateFlags(); })
    ).subscribe({
      next: (res) => this.showSnackbar(res.message || 'Index rebuild initiated!'),
      error: (err) => this.handleOperationError('Rebuild Index', err)
    });
  }

  deleteUploadedFile(fileName: string): void {
    if (confirm(`Delete '${fileName}'?`)) this.showSnackbar(`Delete '${fileName}' not implemented.`, true, 5000);
  }

  private handleOperationError(operation: string, error: HttpErrorResponse): void {
    const errMsg = error.error?.error || error.error?.message || error.message || 'Server error';
    this.showSnackbar(`${operation} failed: ${errMsg}`, true, 5000);
  }

  showSnackbar(message: string, isError: boolean = false, duration: number = 4000): void {
    this.snackBar.open(message, 'Close', {
      duration, horizontalPosition: 'center', verticalPosition: 'top', panelClass: isError ? ['snackbar-error'] : ['snackbar-success']
    });
  }
}
