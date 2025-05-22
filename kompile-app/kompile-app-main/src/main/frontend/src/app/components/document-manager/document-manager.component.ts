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

import { Component, OnInit, ChangeDetectorRef, ViewChild } from '@angular/core'; // Added ViewChild
import { DocumentService } from '../../services/document.service';
import { AnseriniService } from '../../services/anserini.service';
import {
  AddUrlRequest,
  FileUploadResponse,
  SimpleMessageResponse,
  LoaderInfo,
  ChunkerInfo, // Added ChunkerInfo
  BatchProcessRequest,
  BatchLoadRequestItem,
  DocumentSourceType,
  BatchProcessResponse,
  BatchProcessResponseDetails
} from '../../models/api-models';
import { HttpErrorResponse } from '@angular/common/http';

// Angular Material
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource } from '@angular/material/table';
import { MatPaginator } from '@angular/material/paginator'; // For table pagination
import { MatSort } from '@angular/material/sort'; // For table sorting

// Import the dialog component
import { AddSourceDialogComponent, AddSourceDialogResult } from './add-source-dialog/add-source-dialog.component'; // Adjust path if needed

export interface ConfiguredSourceElement {
  id: number;
  path: string;
  type: 'Property' | 'Upload Path'; // Indicates if it's from app.properties or the uploads folder itself
}

export interface UploadedFileElement {
  id: number;
  name: string;
  // location: string; // Can be added if needed, or shown in a tooltip
}

@Component({
  standalone: false,
  selector: 'app-document-manager',
  templateUrl: './document-manager.component.html',
  styleUrls: ['./document-manager.component.css']
})
export class DocumentManagerComponent implements OnInit {
  // Table Data Sources
  configuredSourcesDataSource = new MatTableDataSource<ConfiguredSourceElement>();
  displayedColumnsConfiguredSources: string[] = ['id', 'path', 'type'];

  uploadedFilesDataSource = new MatTableDataSource<UploadedFileElement>();
  displayedColumnsUploadedFiles: string[] = ['id', 'name', 'actions'];

  @ViewChild('configuredSourcesPaginator') configuredSourcesPaginator!: MatPaginator;
  @ViewChild('configuredSourcesSort') configuredSourcesSort!: MatSort;
  @ViewChild('uploadedFilesPaginator') uploadedFilesPaginator!: MatPaginator;
  @ViewChild('uploadedFilesSort') uploadedFilesSort!: MatSort;


  availableLoaders: LoaderInfo[] = [];
  availableChunkers: ChunkerInfo[] = []; // Added availableChunkers

  // Original data arrays
  private _configuredSources: string[] = [];
  private _uploadedFiles: string[] = [];
  uploadedFilesLocation: string = 'N/A';


  // Batch Processing
  batchSourcePaths: string = '';
  batchSelectedLoader: string = '';
  isBatchProcessing: boolean = false;
  batchResults: BatchProcessResponseDetails | null = null;

  // General loading state
  isLoading: boolean = false;

  objectKeys = Object.keys;

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

  refreshAllData(): void {
    this.isLoading = true;
    // Sequentially load data
    this.documentService.getAvailableLoaders().subscribe({
      next: loaders => this.availableLoaders = loaders || [],
      error: err => this.handleLoadError('available loaders', err),
      complete: () => {
        this.documentService.getAvailableChunkers().subscribe({ // Load chunkers
          next: chunkers => this.availableChunkers = chunkers || [],
          error: err => this.handleLoadError('available chunkers', err),
          complete: () => {
            this.documentService.getUploadedFiles().subscribe({
              next: uploadedFilesData => {
                this._uploadedFiles = uploadedFilesData?.files || [];
                this.uploadedFilesLocation = uploadedFilesData?.uploaded_files_location || 'N/A';
                this.updateUploadedFilesTable();
                // Now load configured sources
                this.documentService.getConfiguredSources().subscribe({
                  next: sources => {
                    this._configuredSources = sources || [];
                    this.updateConfiguredSourcesTable();
                    this.isLoading = false;
                    this.cdr.detectChanges();
                  },
                  error: err => this.handleLoadError('configured sources', err, true)
                });
              },
              error: err => this.handleLoadError('uploaded files', err, true)
            });
          }
        });
      }
    });
  }

  private handleLoadError(dataType: string, error: HttpErrorResponse, stopLoading: boolean = false) {
    this.showSnackbar(`Error loading ${dataType}: ${error.message || 'Server error'}`, true);
    if (dataType === 'available loaders') this.availableLoaders = [];
    if (dataType === 'available chunkers') this.availableChunkers = []; // Handle chunker error
    if (dataType === 'uploaded files') {
      this._uploadedFiles = [];
      this.uploadedFilesLocation = 'Error loading path';
      this.updateUploadedFilesTable();
    }
    if (dataType === 'configured sources') {
      this._configuredSources = [];
      this.updateConfiguredSourcesTable();
    }
    if (stopLoading) this.isLoading = false;
    this.cdr.detectChanges();
  }


  updateConfiguredSourcesTable(): void {
    const tableData: ConfiguredSourceElement[] = this._configuredSources
      .filter(s => !s.toLowerCase().includes("no primary document sources configured"))
      .map((s, index) => ({ id: index + 1, path: s, type: 'Property' }));

    if (this.uploadedFilesLocation && this.uploadedFilesLocation !== 'N/A' && !this.uploadedFilesLocation.includes("error_uploads_path_not_configured")) {
      if (!tableData.some(item => item.path === this.uploadedFilesLocation && item.type === 'Upload Path')) {
        tableData.push({
          id: tableData.length + 1,
          path: this.uploadedFilesLocation,
          type: 'Upload Path'
        });
      }
    }
    this.configuredSourcesDataSource.data = tableData;
    setTimeout(() => { // Ensure paginator and sort are picked up after data is set
      if (this.configuredSourcesPaginator) {
        this.configuredSourcesDataSource.paginator = this.configuredSourcesPaginator;
      }
      if (this.configuredSourcesSort) {
        this.configuredSourcesDataSource.sort = this.configuredSourcesSort;
      }
    });
  }

  updateUploadedFilesTable(): void {
    this.uploadedFilesDataSource.data = this._uploadedFiles.map((f, index) => ({
      id: index + 1,
      name: f,
    }));
    setTimeout(() => {
      if (this.uploadedFilesPaginator) {
        this.uploadedFilesDataSource.paginator = this.uploadedFilesPaginator;
      }
      if (this.uploadedFilesSort) {
        this.uploadedFilesDataSource.sort = this.uploadedFilesSort;
      }
    });
  }

  applyFilterConfigured(event: Event) {
    const filterValue = (event.target as HTMLInputElement).value;
    this.configuredSourcesDataSource.filter = filterValue.trim().toLowerCase();
    if (this.configuredSourcesDataSource.paginator) {
      this.configuredSourcesDataSource.paginator.firstPage();
    }
  }

  applyFilterUploaded(event: Event) {
    const filterValue = (event.target as HTMLInputElement).value;
    this.uploadedFilesDataSource.filter = filterValue.trim().toLowerCase();
    if (this.uploadedFilesDataSource.paginator) {
      this.uploadedFilesDataSource.paginator.firstPage();
    }
  }

  openAddSourceDialog(): void {
    const dialogRef = this.dialog.open(AddSourceDialogComponent, {
      width: '600px', // Increased width for better form layout
      data: { availableLoaders: this.availableLoaders },
      disableClose: true // Prevent closing by clicking outside or pressing Esc
    });

    dialogRef.afterClosed().subscribe((result: AddSourceDialogResult | undefined) => {
      if (result) {
        this.submitDocumentSource(result);
      }
    });
  }

  private submitDocumentSource(data: AddSourceDialogResult): void {
    this.isLoading = true; // Use general isLoading here for feedback
    if (data.file) {
      this.documentService.uploadFile(data.file, data.selectedLoader).subscribe({
        next: (response) => {
          this.showSnackbar(response.message || 'File uploaded successfully!');
          this.refreshAllData(); // Refresh all data including uploaded files list
        },
        error: (err) => this.handleOperationError('File upload', err),
        complete: () => this.isLoading = false
      });
    } else if (data.url) {
      const request: AddUrlRequest = {
        url: data.url,
        fileName: data.fileName,
        loader: data.selectedLoader
      };
      this.documentService.addUrl(request).subscribe({
        next: (response) => {
          this.showSnackbar(response.message || 'URL added successfully!');
          this.refreshAllData();
        },
        error: (err) => this.handleOperationError('Add URL', err),
        complete: () => this.isLoading = false
      });
    } else {
      this.isLoading = false; // Should not happen if dialog result is validated
    }
  }

  onProcessBatch(): void {
    if (!this.batchSourcePaths.trim()) {
      this.showSnackbar('Please enter source paths for batch processing.', true);
      return;
    }
    if (!this.batchSelectedLoader) {
      this.showSnackbar('Please select a loader for batch processing.', true);
      return;
    }

    this.isBatchProcessing = true;
    this.batchResults = null; // Clear previous results

    const batchItems: BatchLoadRequestItem[] = this.batchSourcePaths.split(',')
      .map(p => p.trim()).filter(p => p)
      .map(pathOrUrl => ({
        pathOrUrl: pathOrUrl,
        type: pathOrUrl.toLowerCase().startsWith('http') ? DocumentSourceType.URL : DocumentSourceType.FILE,
        loaderName: this.batchSelectedLoader,
        originalFileName: undefined // Currently not collected per item from UI
      }));

    if (batchItems.length === 0) {
      this.showSnackbar("No valid source paths provided for batch processing.", true);
      this.isBatchProcessing = false;
      return;
    }

    const request: BatchProcessRequest = { items: batchItems, defaultLoaderName: this.batchSelectedLoader };

    this.documentService.processBatch(request).subscribe({
      next: (response) => {
        this.batchResults = response.details || null;
        const message = `${response.message || 'Batch processing completed.'} Successful: ${response.successful_items}, Failed: ${response.failed_items}.`;
        this.showSnackbar(message);
        this.isBatchProcessing = false;
        this.batchSourcePaths = ''; // Clear input
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.handleOperationError('Batch processing', err);
        this.isBatchProcessing = false;
      }
    });
  }

  onRebuildIndex(): void {
    this.isLoading = true;
    this.anseriniService.rebuildIndex().subscribe({
      next: (response) => {
        this.showSnackbar(response.message || 'Index rebuild initiated successfully!');
        this.isLoading = false;
      },
      error: (err) => {
        this.handleOperationError('Rebuild Index', err);
        this.isLoading = false;
      }
    });
  }

  deleteUploadedFile(fileName: string): void {
    if (confirm(`Are you sure you want to delete '${fileName}' from the uploads directory? This action requires backend support.`)) {
      this.showSnackbar(`Backend endpoint for deleting '${fileName}' is not yet implemented.`, true, 5000);
      // TODO: Implement this.documentService.deleteUploadedFile(fileName).subscribe(...);
      // After successful deletion, call this.refreshAllData();
    }
  }

  private handleOperationError(operation: string, error: HttpErrorResponse): void {
    const errorMessage = error.error?.error || error.error?.message || error.message || 'Server error';
    this.showSnackbar(`${operation} failed: ${errorMessage}`, true, 5000);
  }

  showSnackbar(message: string, isError: boolean = false, duration: number = 4000): void {
    this.snackBar.open(message, 'Close', {
      duration: duration,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? ['snackbar-error'] : ['snackbar-success'] // Ensure panelClass is an array
    });
  }
}
