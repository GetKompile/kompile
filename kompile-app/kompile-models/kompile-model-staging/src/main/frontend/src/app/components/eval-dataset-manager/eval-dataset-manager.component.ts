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
import { ExperimentService } from '../../services/experiment.service';
import { EvalDataset, DatasetRow } from '../../models/api-models';

@Component({
  selector: 'app-eval-dataset-manager',
  standalone: false,
  templateUrl: './eval-dataset-manager.component.html',
  styleUrls: ['./eval-dataset-manager.component.css']
})
export class EvalDatasetManagerComponent implements OnInit {

  datasets: EvalDataset[] = [];
  loading = false;

  // Upload
  showUpload = false;
  uploadName = '';
  uploadDescription = '';
  selectedFile: File | null = null;

  // Preview
  previewDatasetId: string | null = null;
  previewRows: DatasetRow[] = [];
  previewDisplayedColumns = ['name', 'query', 'expectedAnswer'];

  constructor(
    private experimentService: ExperimentService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadDatasets();
  }

  loadDatasets(): void {
    this.loading = true;
    this.experimentService.listDatasets().subscribe({
      next: (datasets) => {
        this.datasets = datasets;
        this.loading = false;
      },
      error: (err) => {
        this.snackBar.open('Failed to load datasets: ' + err.message, 'Close', { duration: 5000 });
        this.loading = false;
      }
    });
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.selectedFile = input.files[0];
      if (!this.uploadName) {
        this.uploadName = this.selectedFile.name.replace(/\.[^.]+$/, '');
      }
    }
  }

  uploadDataset(): void {
    if (!this.selectedFile || !this.uploadName) {
      this.snackBar.open('File and name are required', 'Close', { duration: 3000 });
      return;
    }

    this.experimentService.uploadDataset(this.selectedFile, this.uploadName, this.uploadDescription).subscribe({
      next: (dataset) => {
        this.snackBar.open(`Dataset uploaded: ${dataset.sampleCount} samples`, 'Close', { duration: 3000 });
        this.showUpload = false;
        this.uploadName = '';
        this.uploadDescription = '';
        this.selectedFile = null;
        this.loadDatasets();
      },
      error: (err) => {
        this.snackBar.open('Upload failed: ' + err.message, 'Close', { duration: 5000 });
      }
    });
  }

  deleteDataset(id: string): void {
    if (confirm('Delete this dataset and its test cases?')) {
      this.experimentService.deleteDataset(id).subscribe({
        next: () => {
          this.snackBar.open('Dataset deleted', 'Close', { duration: 3000 });
          if (this.previewDatasetId === id) {
            this.previewDatasetId = null;
            this.previewRows = [];
          }
          this.loadDatasets();
        },
        error: (err) => {
          this.snackBar.open('Delete failed: ' + err.message, 'Close', { duration: 5000 });
        }
      });
    }
  }

  togglePreview(id: string): void {
    if (this.previewDatasetId === id) {
      this.previewDatasetId = null;
      this.previewRows = [];
      return;
    }

    this.previewDatasetId = id;
    this.experimentService.previewDataset(id, 20).subscribe({
      next: (rows) => this.previewRows = rows,
      error: (err) => {
        this.snackBar.open('Preview failed: ' + err.message, 'Close', { duration: 5000 });
      }
    });
  }
}
