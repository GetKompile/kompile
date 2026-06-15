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

import { Component, OnInit } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DatasetService } from '../../services/dataset.service';
import { DatasetInfo, DatasetStats, DatasetFormat, DatasetTask } from '../../models/api-models';

@Component({
  selector: 'app-dataset-manager',
  standalone: false,
  templateUrl: './dataset-manager.component.html',
  styleUrls: ['./dataset-manager.component.css']
})
export class DatasetManagerComponent implements OnInit {
  datasets: DatasetInfo[] = [];
  loading = false;
  uploading = false;
  selectedDataset: DatasetInfo | null = null;
  previewData: any[] = [];
  showUploadForm = false;

  // Upload form
  uploadName = '';
  uploadFormat: DatasetFormat = 'JSONL';
  uploadTask: DatasetTask = 'CAUSAL_LM';
  uploadInputColumn = 'text';
  uploadOutputColumn = '';
  uploadChosenColumn = 'chosen';
  uploadRejectedColumn = 'rejected';
  uploadTrainSplit = 0.9;
  selectedFile: File | null = null;

  formats: DatasetFormat[] = ['JSONL', 'CSV', 'PARQUET', 'TEXT'];
  tasks: DatasetTask[] = ['CAUSAL_LM', 'SEQ2SEQ', 'CLASSIFICATION', 'PREFERENCE'];

  displayedColumns = ['name', 'format', 'task', 'totalSamples', 'size', 'createdAt', 'actions'];

  constructor(
    private datasetService: DatasetService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadDatasets();
  }

  loadDatasets(): void {
    this.loading = true;
    this.datasetService.listDatasets().subscribe({
      next: (datasets) => { this.datasets = datasets; this.loading = false; },
      error: (err) => { this.snackBar.open('Failed to load datasets: ' + err.message, 'Close', { duration: 5000 }); this.loading = false; }
    });
  }

  onFileSelected(event: any): void {
    this.selectedFile = event.target.files[0];
    if (this.selectedFile && !this.uploadName) {
      this.uploadName = this.selectedFile.name.replace(/\.[^/.]+$/, '');
    }
  }

  uploadDataset(): void {
    if (!this.selectedFile || !this.uploadName) return;
    this.uploading = true;
    this.datasetService.uploadDataset({
      name: this.uploadName,
      format: this.uploadFormat,
      task: this.uploadTask,
      inputColumn: this.uploadInputColumn,
      outputColumn: this.uploadOutputColumn,
      chosenColumn: this.uploadChosenColumn,
      rejectedColumn: this.uploadRejectedColumn,
      trainSplit: this.uploadTrainSplit
    }, this.selectedFile).subscribe({
      next: (info) => {
        this.snackBar.open('Dataset uploaded successfully', 'Close', { duration: 3000 });
        this.uploading = false;
        this.showUploadForm = false;
        this.resetUploadForm();
        this.loadDatasets();
      },
      error: (err) => {
        this.snackBar.open('Upload failed: ' + err.message, 'Close', { duration: 5000 });
        this.uploading = false;
      }
    });
  }

  resetUploadForm(): void {
    this.uploadName = '';
    this.uploadFormat = 'JSONL';
    this.uploadTask = 'CAUSAL_LM';
    this.uploadInputColumn = 'text';
    this.uploadOutputColumn = '';
    this.selectedFile = null;
  }

  selectDataset(dataset: DatasetInfo): void {
    this.selectedDataset = dataset;
    this.previewData = [];
    this.datasetService.previewDataset(dataset.id).subscribe({
      next: (data) => this.previewData = data,
      error: () => {}
    });
  }

  deleteDataset(id: string): void {
    if (!confirm('Delete this dataset?')) return;
    this.datasetService.deleteDataset(id).subscribe({
      next: () => { this.snackBar.open('Dataset deleted', 'Close', { duration: 3000 }); this.loadDatasets(); if (this.selectedDataset?.id === id) this.selectedDataset = null; },
      error: (err) => this.snackBar.open('Delete failed: ' + err.message, 'Close', { duration: 5000 })
    });
  }

  computeStats(id: string): void {
    this.datasetService.getStats(id).subscribe({
      next: (stats) => {
        if (this.selectedDataset?.id === id) {
          this.selectedDataset = { ...this.selectedDataset, stats };
        }
        this.loadDatasets();
      },
      error: (err) => this.snackBar.open('Stats computation failed: ' + err.message, 'Close', { duration: 5000 })
    });
  }

  formatBytes(bytes: number): string {
    if (!bytes) return 'N/A';
    if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(2) + ' GB';
    if (bytes >= 1048576) return (bytes / 1048576).toFixed(2) + ' MB';
    if (bytes >= 1024) return (bytes / 1024).toFixed(2) + ' KB';
    return bytes + ' B';
  }
}
