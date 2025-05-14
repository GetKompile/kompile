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

import { Component, OnInit } from '@angular/core';
import { DocumentService } from '../../services/document.service';
import { AnseriniService } from '../../services/anserini.service';
import { AddUrlRequest, FileUploadResponse, SimpleMessageResponse } from '../../models/api-models';
import { HttpErrorResponse } from '@angular/common/http';

@Component({
  standalone: false,
  selector: 'app-document-manager',
  templateUrl: './document-manager.component.html',
  styleUrls: ['./document-manager.component.css']
})
export class DocumentManagerComponent implements OnInit {
  configuredSources: string[] = [];
  uploadedFiles: string[] = [];
  uploadedFilesLocation: string = '';

  selectedFile: File | null = null;
  addUrlValue: string = '';
  addUrlFileName: string = '';

  message: string | null = null;
  errorMessage: string | null = null;
  isLoading: boolean = false;

  constructor(
    private documentService: DocumentService,
    private anseriniService: AnseriniService
  ) { }

  ngOnInit(): void {
    this.loadConfiguredSources();
    this.loadUploadedFiles();
  }

  loadConfiguredSources(): void {
    this.isLoading = true;
    this.documentService.getConfiguredSources().subscribe({
      next: (sources) => {
        this.configuredSources = sources;
        this.isLoading = false;
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage = `Failed to load configured sources: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  loadUploadedFiles(): void {
    this.isLoading = true;
    this.documentService.getUploadedFiles().subscribe({
      next: (data) => {
        this.uploadedFiles = data.files;
        this.uploadedFilesLocation = data.uploaded_files_location;
        this.isLoading = false;
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage = `Failed to load uploaded files: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  onFileSelected(event: Event): void {
    const element = event.currentTarget as HTMLInputElement;
    let fileList: FileList | null = element.files;
    if (fileList && fileList.length > 0) {
      this.selectedFile = fileList[0];
      this.message = `Selected file: ${this.selectedFile.name}`;
      this.errorMessage = null;
    } else {
      this.selectedFile = null;
    }
  }

  onUploadFile(): void {
    if (!this.selectedFile) {
      this.errorMessage = 'Please select a file to upload.';
      return;
    }
    this.isLoading = true;
    this.errorMessage = null;
    this.message = null;
    this.documentService.uploadFile(this.selectedFile).subscribe({
      next: (response: FileUploadResponse) => {
        this.message = response.message || 'File uploaded successfully!';
        if (response.next_step) {
          this.message += ` Next: ${response.next_step}`;
        }
        this.selectedFile = null; // Reset file input
        (document.getElementById('fileUploadInput') as HTMLInputElement).value = ''; // Reset file input display
        this.loadUploadedFiles(); // Refresh list
        this.isLoading = false;
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage = `File upload failed: ${err.message || (err.error && err.error.error) || 'Server error'}`;
        this.isLoading = false;
      }
    });
  }

  onAddUrl(): void {
    if (!this.addUrlValue.trim()) {
      this.errorMessage = 'Please enter a URL.';
      return;
    }
    this.isLoading = true;
    this.errorMessage = null;
    this.message = null;
    const request: AddUrlRequest = { url: this.addUrlValue, fileName: this.addUrlFileName || undefined };
    this.documentService.addUrl(request).subscribe({
      next: (response: FileUploadResponse) => {
        this.message = response.message || 'URL added successfully!';
        if (response.next_step) {
          this.message += ` Next: ${response.next_step}`;
        }
        this.addUrlValue = '';
        this.addUrlFileName = '';
        this.loadUploadedFiles(); // Refresh list
        this.isLoading = false;
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage = `Failed to add URL: ${err.message || (err.error && err.error.error) || 'Server error'}`;
        this.isLoading = false;
      }
    });
  }

  onRebuildIndex(): void {
    this.isLoading = true;
    this.errorMessage = null;
    this.message = null;
    this.anseriniService.rebuildIndex().subscribe({
      next: (response: SimpleMessageResponse) => {
        this.message = response.message || 'Index rebuild initiated successfully!';
        this.isLoading = false;
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage = `Failed to rebuild index: ${err.message || (err.error && err.error.error) || 'Server error'}`;
        this.isLoading = false;
      }
    });
  }
}
