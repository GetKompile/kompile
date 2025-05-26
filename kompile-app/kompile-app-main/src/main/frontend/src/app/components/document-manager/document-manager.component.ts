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

import { Component, OnInit, ChangeDetectorRef, ViewChild, AfterViewInit, ElementRef } from '@angular/core';
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
  DocumentSummary,
  DebuggerStatus,
  DebugAnalysisResult,
  TestUploadResponse
} from '../../models/api-models';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource } from '@angular/material/table';
import { MatPaginator } from '@angular/material/paginator';
import { MatSort } from '@angular/material/sort';
import { AddSourceDialogComponent } from './add-source-dialog/add-source-dialog.component';
import { forkJoin, of, Observable } from 'rxjs';
import { catchError, finalize, map, startWith } from 'rxjs/operators';

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

  // Debugger Properties
  debuggerStatus: DebuggerStatus | null = null;
  isLoadingDebuggerStatus: boolean = false;
  selectedFileForDebug: string = '';
  selectedLoaderForDebug: string = '';
  selectedChunkerForDebug: string = '';
  debugAnalysisResult: DebugAnalysisResult | null = null;
  isAnalyzingFile: boolean = false;
  showDebugStatusCard: boolean = false;
  showDebugAnalysisCard: boolean = false;
  debugTestUploadFile: File | null = null;
  isTestingUpload: boolean = false;

  constructor(
    private documentService: DocumentService,
    private anseriniService: AnseriniService,
    public dialog: MatDialog,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) { }

  ngOnInit(): void {
    this.refreshAllData();
    this.loadDebuggerStatus();
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
    const baseLoading = this.isLoading || this.isLoadingDebuggerStatus || this.isBatchProcessing || this.isAnalyzingFile || this.isTestingUpload;

    this.showLoadersSpinner = baseLoading && (!this.availableLoaders || this.availableLoaders.length === 0);
    this.showNoLoadersMessage = !baseLoading && (!this.availableLoaders || this.availableLoaders.length === 0);
    this.showLoadersList = !!(this.availableLoaders && this.availableLoaders.length > 0);

    this.showChunkersSpinner = baseLoading && (!this.availableChunkers || this.availableChunkers.length === 0);
    this.showNoChunkersMessage = !baseLoading && (!this.availableChunkers || this.availableChunkers.length === 0);
    this.showChunkersList = !!(this.availableChunkers && this.availableChunkers.length > 0);

    this.showConfiguredSourcesSpinner = baseLoading && this.configuredSourcesDataSource.data.length === 0;
    this.showNoConfiguredSourcesMessage = !baseLoading && this.configuredSourcesDataSource.data.length === 0;
    this.showConfiguredSourcesTable = this.configuredSourcesDataSource.data.length > 0;

    this.showUploadedFilesSpinner = baseLoading && this.uploadedFilesDataSource.data.length === 0;
    this.showNoUploadedFilesMessage = !baseLoading && this.uploadedFilesDataSource.data.length === 0;
    this.showUploadedFilesTable = this.uploadedFilesDataSource.data.length > 0;

    this.showBatchResults = !!(this.batchResults && !this.isBatchProcessing && (this.batchResultPaths?.length || 0) > 0);
    this.showDebugStatusCard = !!this.debuggerStatus && !this.isLoadingDebuggerStatus;
    this.showDebugAnalysisCard = !!this.debugAnalysisResult && !this.isAnalyzingFile;
    this.cdr.detectChanges();
  }

  refreshAllData(callback?: () => void): void {
    if (this.isLoading && !callback) { // Prevent multiple parallel refreshes unless a callback is provided
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
      error: (err) => { // This error block might be redundant due to individual catchErrors, but good for a global fallback
        this.showSnackbar(`An error occurred while loading initial data: ${err.message || 'Server error'}`, true);
      }
    });
  }

  private handleLoadError(dataType: string, error: HttpErrorResponse) {
    this.showSnackbar(`Error loading ${dataType}: ${error.message || 'Server error'}`, true, 6000);
    if (dataType === 'available loaders') this.availableLoaders = [];
    if (dataType === 'available chunkers') this.availableChunkers = [];
    if (dataType === 'uploaded files') {
      this._uploadedFiles = []; this.uploadedFilesLocation = 'Error loading path'; this.updateUploadedFilesTable();
    }
    if (dataType === 'configured sources') {
      this._configuredSources = []; this.updateConfiguredSourcesTable();
    }
  }

  getSimpleName(className: any): string {
    const nameStr = this.safeToString(className);
    const parts = nameStr.split('.');
    return parts.pop() || nameStr; // Return full string if no dots
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

  hasValidMetadata(summary: DocumentSummary | undefined | null): boolean {
    if (!summary || !summary.metadata) return false;
    try {
      JSON.stringify(summary.metadata); // Test for circular refs
      return Object.keys(summary.metadata).length > 0;
    } catch (error) {
      console.warn('Metadata contains circular references or is invalid:', summary.metadata, error);
      return false; // Treat as invalid
    }
  }

  getSafeMetadata(summary: DocumentSummary | undefined | null): any {
    if (!this.hasValidMetadata(summary)) {
      return {}; // Return empty or some placeholder if not valid
    }
    try {
      return JSON.parse(JSON.stringify(summary!.metadata)); // Deep copy
    } catch (error) {
      console.warn('Error creating safe metadata copy:', summary!.metadata, error);
      return { "error": "Could not display metadata due to parsing issues." };
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

  safeToString(value: any): string {
    if (value === null || value === undefined) {
      return '';
    }
    if (typeof value === 'string') {
      return value;
    }
    try {
      return String(value);
    } catch (e) {
      return '';
    }
  }

  safeLength(value: any): number {
    return this.safeToString(value).length;
  }

  // TrackBy functions for ngFor performance optimization (NEW)
  trackByLoaderName(index: number, loader: LoaderInfo): string {
    return loader.name || index.toString();
  }

  trackByChunkerName(index: number, chunker: ChunkerInfo): string {
    return chunker.name || index.toString();
  }

  updateConfiguredSourcesTable(): void {
    const tableData: ConfiguredSourceElement[] = (this._configuredSources || [])
      .filter(s => s && !s.toLowerCase().includes("no primary document sources configured"))
      .map((s, index) => ({ id: index + 1, path: this.safeToString(s), type: 'Property' }));

    if (this.uploadedFilesLocation && this.uploadedFilesLocation !== 'N/A' && !this.uploadedFilesLocation.includes("error_uploads_path_not_configured") && !this.uploadedFilesLocation.includes("Error loading path")) {
      if (!tableData.some(item => item.path === this.uploadedFilesLocation && item.type === 'Upload Path')) {
        tableData.push({
          id: tableData.length + 1,
          path: this.safeToString(this.uploadedFilesLocation),
          type: 'Upload Path'
        });
      }
    }
    this.configuredSourcesDataSource.data = tableData;
    this.assignPaginatorsAndSorters(); // Re-assign after data change
    this.updateTemplateFlags();
  }

  updateUploadedFilesTable(): void {
    this.uploadedFilesDataSource.data = (this._uploadedFiles || []).map((f, index) => ({
      id: index + 1,
      name: this.safeToString(f),
    }));
    this.assignPaginatorsAndSorters(); // Re-assign after data change
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
      this.refreshAllData(() => { // Refresh all data after success
        if (data?.rebuildIndex) {
          this.onRebuildIndex(); // isLoading will be handled by onRebuildIndex
        } else {
          this.isLoading = false;
          this.updateTemplateFlags();
        }
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
    } else {
      this.isLoading = false; this.updateTemplateFlags(); // No action taken
    }
  }

  onProcessBatch(): void {
    if (!this.hasBatchSourceContent) { this.showSnackbar('Please enter source paths/URLs for batch processing.', true); return; }
    if (!this.batchSelectedLoader) { this.showSnackbar('Please select a loader for batch processing.', true); return; }
    this.isBatchProcessing = true; this.batchResults = null; this.batchResultPaths = []; this.updateTemplateFlags();
    const items: BatchLoadRequestItem[] = this.batchSourcePaths.split(',')
      .map(p => p.trim()).filter(p => p)
      .map(pathOrUrl => ({
        pathOrUrl,
        type: pathOrUrl.toLowerCase().startsWith('http://') || pathOrUrl.toLowerCase().startsWith('https://') ? DocumentSourceType.URL : DocumentSourceType.FILE,
        loaderName: this.batchSelectedLoader
      }));

    if (items.length === 0) {
      this.showSnackbar("No valid source paths or URLs provided for batch processing.", true);
      this.isBatchProcessing = false; this.updateTemplateFlags(); return;
    }

    this.documentService.processBatch({ items, defaultLoaderName: this.batchSelectedLoader }).pipe(
      finalize(() => { this.isBatchProcessing = false; this.updateTemplateFlags(); })
    ).subscribe({
      next: (res) => {
        this.batchResults = res.details || null;
        this.batchResultPaths = this.batchResults ? Object.keys(this.batchResults) : [];
        this.showSnackbar(`${res.message || 'Batch process complete.'} Successful: ${res.successful_items}, Failed: ${res.failed_items}.`);
        this.batchSourcePaths = ''; // Clear input after processing
        this.refreshAllData(); // Refresh data to reflect any new files from batch processing
      },
      error: (err) => { this.handleOperationError('Batch processing', err); this.batchResults = null; this.batchResultPaths = []; }
    });
  }

  onRebuildIndex(): void {
    this.isLoading = true; this.updateTemplateFlags(); // Use isLoading for general loading state
    this.anseriniService?.rebuildIndex().pipe(
      finalize(() => { this.isLoading = false; this.updateTemplateFlags(); })
    ).subscribe({
      next: (res) => this.showSnackbar(res.message || 'Index rebuild initiated successfully!'),
      error: (err) => this.handleOperationError('Rebuild Index', err)
    });
  }

  deleteUploadedFile(fileName: string): void {
    if (confirm(`Are you sure you want to attempt to delete '${fileName}'? This action is frontend-only and might not persist if the backend doesn't support deletion or if the file is managed externally.`)) {
      // For now, only show a snackbar as backend deletion is not specified.
      // If a backend deletion endpoint exists, it should be called here.
      this.showSnackbar(`Deletion for '${fileName}' not fully implemented (no backend call). File might reappear on refresh.`, true, 7000);

      // Optimistic UI update (optional, file will reappear if not deleted on backend and refreshAllData is called)
      // this._uploadedFiles = this._uploadedFiles.filter(f => f !== fileName);
      // this.updateUploadedFilesTable();
    }
  }

  // --- Document Debugger Methods ---

  loadDebuggerStatus(): void {
    this.isLoadingDebuggerStatus = true;
    this.debuggerStatus = null;
    this.updateTemplateFlags();
    this.documentService.getDebuggerStatus().pipe(
      finalize(() => {
        this.isLoadingDebuggerStatus = false;
        this.updateTemplateFlags();
      })
    ).subscribe({
      next: (status) => {
        this.debuggerStatus = status;
      },
      error: (err) => {
        this.handleOperationError('Loading debugger status', err);
        this.debuggerStatus = { // Provide a default error status
          uploadsPathConfigured: false, uploadsPath: 'Error loading status',
          totalLoaders: 0, realLoaders: 0, noOpLoaders: 0,
          totalChunkers: 0, realChunkers: 0, noOpChunkers: 0
        };
      }
    });
  }

  onFileSelectedForDebug(fileName: string): void {
    this.selectedFileForDebug = fileName;
    this.debugAnalysisResult = null;
    this.selectedLoaderForDebug = ''; // Reset loader
    this.selectedChunkerForDebug = ''; // Reset chunker
    this.showDebugAnalysisCard = false;
    this.updateTemplateFlags();
    // Optionally trigger analyzeSelectedFile() here or have a dedicated button
  }

  analyzeSelectedFile(): void {
    if (!this.selectedFileForDebug) {
      this.showSnackbar('Please select a file to analyze from the "Uploaded Files" table.', true);
      return;
    }
    this.isAnalyzingFile = true;
    this.debugAnalysisResult = null;
    this.updateTemplateFlags();

    this.documentService.analyzeFile(
      this.selectedFileForDebug,
      this.selectedLoaderForDebug || undefined, // Pass undefined if empty for auto-select
      this.selectedChunkerForDebug || undefined // Pass undefined if empty for auto-select
    ).pipe(
      finalize(() => {
        this.isAnalyzingFile = false;
        this.updateTemplateFlags();
      })
    ).subscribe({
      next: (result) => {
        this.debugAnalysisResult = result;
        if (result.errorMessage) {
          this.showSnackbar(`Analysis Error: ${result.errorMessage}`, true, 7000);
        } else {
          this.showSnackbar(`Analysis complete for ${result.fileName}.`, false);
        }
      },
      error: (err) => {
        this.handleOperationError(`Analyzing file ${this.selectedFileForDebug}`, err);
        this.debugAnalysisResult = { // Provide a default error result
          fileName: this.selectedFileForDebug, filePath: null, fileSize: 0,
          availableLoaders: null, selectedLoader: null, loadedDocuments: null,
          availableChunkers: null, selectedChunker: null, chunks: null,
          processingStats: null, errorMessage: `Client-side error or unhandled server error: ${err.message || 'Unknown error'}`
        };
      }
    });
  }

  onDebugFileChange(event: Event): void {
    const element = event.currentTarget as HTMLInputElement;
    const fileList: FileList | null = element.files;
    if (fileList && fileList.length > 0) {
      this.debugTestUploadFile = fileList!![0];
    } else {
      this.debugTestUploadFile = null;
    }
  }

  onTestUploadDebugFile(): void {
    if (!this.debugTestUploadFile) {
      this.showSnackbar('Please select a file to upload for debugging.', true);
      return;
    }
    if (!this.debuggerStatus?.uploadsPathConfigured) {
      this.showSnackbar('Test upload unavailable: Debugger uploads path is not configured on the server.', true, 7000);
      return;
    }

    this.isTestingUpload = true;
    this.updateTemplateFlags();
    this.documentService.testUploadDebugFile(this.debugTestUploadFile).pipe(
      finalize(() => {
        this.isTestingUpload = false;
        this.debugTestUploadFile = null;
        this.updateTemplateFlags();
      })
    ).subscribe({
      next: (response: TestUploadResponse) => {
        if (response.error) {
          this.showSnackbar(`Test upload failed: ${response.error}`, true, 7000);
        } else {
          this.showSnackbar(response.message || 'File uploaded successfully for debugging.', false);
          this.refreshAllData(); // Refresh to show the newly uploaded debug file
        }
      },
      error: (err) => {
        this.handleOperationError('Test file upload for debugging', err);
      }
    });
  }

  private handleOperationError(operation: string, error: HttpErrorResponse): void {
    const errMsg = error.error?.error || error.error?.message || (error.error && typeof error.error === 'string' ? error.error : null) || error.message || 'Server error';
    this.showSnackbar(`${operation} failed: ${errMsg}`, true, 7000);
    console.error(`${operation} failed:`, error);
  }

  showSnackbar(message: string, isError: boolean = false, duration: number = 5000): void {
    this.snackBar.open(message, 'Close', {
      duration,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? ['snackbar-error'] : ['snackbar-success']
    });
  }

  getFileName(element: any): string {
    if (typeof element === 'string') {
      return element;
    }
    if (element && typeof element === 'object') {
      return element.name || element.fileName || element.path || String(element);
    }
    return String(element || '');
  }

  getDisplayFileName(element: any): string {
    const fileName = this.getFileName(element);
    return fileName.length > 50 ? fileName.slice(0, 50) + '...' : fileName;
  }
}
